package io.github.oleksiyp.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.runBlocking

class PipelineBuilderScope(val pipeline: ChannelPipeline,
                           val nettyDispatcher: CoroutineDispatcher) {

    fun <I> ChannelPipeline.addCoroutineHandler(cls: Class<I>,
                                                dispatcher: CoroutineDispatcher = DefaultDispatcher,
                                                requestHandler: suspend NettyScope<I>.() -> Unit) {
        addLast(NettyCoroutineHandler(cls, dispatcher, requestHandler = requestHandler))
    }

    fun ChannelPipeline.addCoroutineHandler(dispatcher: CoroutineDispatcher = DefaultDispatcher,
                                            requestHandler: suspend NettyScope<ByteBuf>.() -> Unit) {
        addCoroutineHandler(ByteBuf::class.java, dispatcher, requestHandler)
    }

    fun ChannelPipeline.addServerHttpCodec(aggregationSize: Int = 512 * 1024) {
        addLast(HttpRequestDecoder())
        addLast(HttpObjectAggregator(aggregationSize))
        addLast(HttpResponseEncoder())
    }

    fun ChannelPipeline.addServerErrorHttpHandler(dispatcher: CoroutineDispatcher = DefaultDispatcher,
                                                  requestHandler: suspend ErrorHttpHandlerScope.() -> Unit) {
        addLast(object : ChannelDuplexHandler() {
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                runBlocking {
                    val internal = NettyScope.Internal<HttpRequest>(ctx.channel(), dispatcher, false)
                    internal.go {
                        ErrorHttpHandlerScope(cause, internal).requestHandler()
                        ctx.channel().close()
                    }
                }
            }
        })
    }

    fun ChannelPipeline.addServerHttpHandler(dispatcher: CoroutineDispatcher = DefaultDispatcher,
                                             requestHandler: suspend RequestHttpHandlerScope.() -> Unit) {
        addCoroutineHandler(HttpRequest::class.java, dispatcher = dispatcher) {
            while (isActive) {
                val request = receive()
                val requestHttp = RequestHttpHandlerScope(request, internal)

                requestHttp.requestHandler()
                requestHttp.end()

                if (!HttpUtil.isKeepAlive(request)) {
                    break
                }
            }
        }
    }

    fun ChannelPipeline.addServerWebSocketHandler(dispatcher: CoroutineDispatcher = DefaultDispatcher,
                                                  maxFragmentSize: Int = 512 * 1024,
                                                  requestHandler: suspend WebSocketHandlerScope.() -> Unit) {

        val handler: suspend NettyScope<WebSocketFrame>.(String) -> Unit = { uri ->
            WebSocketHandlerScope(uri, internal).requestHandler()
        }

        addLast(object : SimpleChannelInboundHandler<HttpRequest>() {
            fun getWebSocketURL(req: HttpRequest) = "ws://" + req.headers().get("Host") + req.uri()

            override fun acceptInboundMessage(msg: Any?): Boolean {
                if (msg !is HttpRequest) {
                    return false
                }

                val headers = msg.headers()
                return headers.get("Connection").equals("Upgrade", ignoreCase = true) ||
                        headers.get("Upgrade").equals("WebSocket", ignoreCase = true)
            }

            override fun channelRead0(ctx: ChannelHandlerContext, req: HttpRequest) {
                val uri = req.uri()
                val coroutineHandler = NettyCoroutineHandler(WebSocketFrame::class.java, dispatcher) {
                    handler(uri)
                }

                coroutineHandler.channelRegistered(ctx)


                ctx.pipeline().replace(this, "webSocketHandler", coroutineHandler)
                ctx.pipeline().addBefore("webSocketHandler", "webSocketAggregator", WebSocketFrameAggregator(maxFragmentSize))

                val webSocketURL = getWebSocketURL(req)
                val wsFactory = WebSocketServerHandshakerFactory(webSocketURL, null, true)
                val handshaker = wsFactory.newHandshaker(req)
                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
                } else {
                    handshaker.handshake(ctx.channel(), req)
                }
            }
        })
    }

}