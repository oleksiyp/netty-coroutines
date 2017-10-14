package io.github.oleksiyp.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import kotlinx.coroutines.experimental.runBlocking

open class NettyServer(port: Int,
                       pipelineBuilder: PipelineBuilderScope.() -> Unit) {


    val bootstrap = ServerBootstrap()
            .group(NioEventLoopGroup(), NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)

    init {
        bootstrap.childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()
                PipelineBuilderScope(pipeline).pipelineBuilder()
            }
        })
        bootstrap.bind(port).sync()
    }

    inner class PipelineBuilderScope(val pipeline: ChannelPipeline) {


        fun <I> ChannelPipeline.addCoroutineHandler(cls: Class<I>,
                                                    requestHandler: suspend NettyScope<I>.() -> Unit) {
            addLast(NettyCoroutineHandler(cls, requestHandler))
        }

        fun ChannelPipeline.addCoroutineHandler(requestHandler: suspend NettyScope<ByteBuf>.() -> Unit) {
            addCoroutineHandler(ByteBuf::class.java, requestHandler)
        }

        fun ChannelPipeline.addServerHttpCodec(aggregationSize: Int = 512 * 1024) {
            pipeline.addLast(HttpRequestDecoder())
            pipeline.addLast(HttpObjectAggregator(aggregationSize))
            pipeline.addLast(HttpResponseEncoder())
        }

        fun ChannelPipeline.addErrorHttpHandler(requestHandler: suspend ErrorHttpHandlerScope.() -> Unit) {
            addLast(object : ChannelDuplexHandler() {
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    runBlocking {
                        val internal = NettyScope.Internal<HttpRequest>(ctx.channel(), false)
                        internal.go {
                            ErrorHttpHandlerScope(cause, internal).requestHandler()
                            ctx.channel().close()
                        }
                    }
                }
            })
        }

        fun ChannelPipeline.addHttpHandler(requestHandler: suspend RequestHttpHandlerScope.() -> Unit) {
            addCoroutineHandler(HttpRequest::class.java) {
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

        fun ChannelPipeline.addWebSocketHandler(maxFragmentSize: Int = 512 * 1024,
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
                    val coroutineHandler = NettyCoroutineHandler(WebSocketFrame::class.java, {
                        handler(uri)
                    })

                    coroutineHandler.channelRegistered(ctx)


                    ctx.pipeline().replace(this, "webSocketHandler",coroutineHandler)
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
}

