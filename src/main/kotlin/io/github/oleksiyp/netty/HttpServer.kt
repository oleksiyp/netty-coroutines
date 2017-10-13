package io.github.oleksiyp.netty

import io.github.oleksiyp.json.JsonContext
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.buffer.Unpooled.copiedBuffer
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpHeaderNames.*
import io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND
import io.netty.handler.codec.http.HttpResponseStatus.OK
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.AttributeKey
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.nio.aRead
import java.nio.ByteBuffer.allocate
import java.nio.channels.AsynchronousFileChannel
import java.nio.charset.Charset
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Matcher
import java.util.regex.Pattern


fun QueryStringDecoder.firstParam(key: String) = parameters()?.get(key)?.getOrNull(0)
fun QueryStringDecoder.firstIntParam(key: String) = firstParam(key)?.toIntOrNull()

class HttpServer(port: Int = 80,
                 private val pipelineBuilder: PipelineBuilderScope.(HttpServer) -> Unit) {


    val bootstrap = ServerBootstrap()
            .group(NioEventLoopGroup(), NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)

    private val httpHandlerContextAttr = AttributeKey.newInstance<HttpHandlerContext>("HTTP_HANDLER_CONTEXT")

    private fun ChannelHandlerContext.httpHandlerContext() = channel().attr(httpHandlerContextAttr).get()

    protected val coroutineContext = bootstrap.config().childGroup().asCoroutineDispatcher()

    init {
        bootstrap.childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()
                pipeline.addLast(LoggingHandler(LogLevel.INFO))
                pipeline.addLast(HttpRequestDecoder())
                pipeline.addLast(HttpObjectAggregator(512 * 1024))
                pipeline.addLast(HttpResponseEncoder())
                PipelineBuilderScope(pipeline).pipelineBuilder(this@HttpServer)
            }
        })
        bootstrap.bind(port).sync()
    }


    inner class PipelineBuilderScope(val pipeline: ChannelPipeline) {

        fun ChannelPipeline.addWebSocketHandler(requestHandler: suspend WebSocketHandlerContext.() -> Unit) {
            addLast(object : SimpleChannelInboundHandler<HttpRequest>() {
                fun getWebSocketURL(req: HttpRequest) = "ws://" + req.headers().get("Host") + req.uri()

                override fun channelRead0(ctx: ChannelHandlerContext, req: HttpRequest) {
                    val headers = req.headers()
                    if (headers.get("Connection").equals("Upgrade", ignoreCase = true) ||
                            headers.get("Upgrade").equals("WebSocket", ignoreCase = true)) {

                        ctx.pipeline().replace(this, "webSocketHandler",
                                object : SimpleChannelInboundHandler<WebSocketFrame>() {
                                    override fun channelRead0(ctx: ChannelHandlerContext, msg: WebSocketFrame) {
                                        launch(coroutineContext) {
                                            val sockCtx = WebSocketHandlerContext(msg.retain(), this::isActive) { ctx.writeAndFlush(it) }
                                            sockCtx.requestHandler()
                                            msg.release()
                                        }
                                    }
                                })

                        ctx.pipeline().addAfter("webSocketHandler", "webSocketAggregator", WebSocketFrameAggregator(512 * 1024))


                        val wsFactory = WebSocketServerHandshakerFactory(getWebSocketURL(req), null, true)
                        val handshaker = wsFactory.newHandshaker(req)
                        if (handshaker == null) {
                            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
                        } else {
                            handshaker.handshake(ctx.channel(), req)
                        }
                    }
                }
            })
        }

        fun ChannelPipeline.addErrorHandler(requestHandler: suspend ErrorHttpHandlerContext.() -> Unit) {
            addLast(object : ChannelDuplexHandler() {
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    runBlocking {
                        val internal = HttpHandlerContextInt()
                        internal.isActive = this::isActive
                        internal.isWriteable = ctx.channel()::isWritable
                        internal.write = { ctx.writeAndFlush(it) }
                        ErrorHttpHandlerContext(cause, internal).requestHandler()
                        ctx.channel().close()
                    }
                }
            })
        }

        fun ChannelPipeline.addHttpHandler(requestHandler: suspend RequestHttpHandlerContext.() -> Unit) {
            addLast(object : SimpleChannelInboundHandler<HttpRequest>() {
                override fun channelRead0(ctx: ChannelHandlerContext, request: HttpRequest) {
                    val internal = HttpHandlerContextInt()
                    val httpCtx = RequestHttpHandlerContext(request, internal)
                    val channel = ctx.channel()

                    val job = launch(coroutineContext) {
                        internal.isActive = this::isActive
                        internal.isWriteable = channel::isWritable
                        internal.write = { channel.writeAndFlush(it) }

                        httpCtx.requestHandler()
                        httpCtx.end()
                        val emptyWrite = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        if (!HttpUtil.isKeepAlive(request)) {
                            emptyWrite.addListener(ChannelFutureListener.CLOSE)
                        }
                    }
                    job.invokeOnCompletion(true) {
                        job.getCancellationException().cause?.let {
                            ctx.fireExceptionCaught(it)
                        }
                    }
                    internal.job = job
                    channel.attr(httpHandlerContextAttr).set(httpCtx)
                }


                override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
                    if (ctx.channel().isWritable) {
                        ctx.httpHandlerContext()?.internal?.resumeWrite()
                    }
                    super.channelWritabilityChanged(ctx)
                }

                override fun channelInactive(ctx: ChannelHandlerContext) {
                    runBlocking(coroutineContext) {
                        ctx.httpHandlerContext()?.internal?.cancel()
                        ctx.channel().attr(httpHandlerContextAttr).set(null)
                    }
                }
            })
        }
    }

    class HttpHandlerContextInt {
        lateinit var isActive: () -> Boolean
        lateinit var isWriteable: () -> Boolean
        lateinit var write: (obj: HttpObject) -> Unit


        suspend fun replyFunc(obj: HttpObject) {
            if (isWriteable()) {
                write(obj)
            } else {
                suspendCancellableCoroutine<Unit> { cont ->
                    writeContinuation.getAndSet(cont)?.resume(Unit)
                }
                write(obj)
            }

        }

        val writeContinuation = AtomicReference<CancellableContinuation<Unit>>()
        lateinit var job: Job

        fun resumeWrite() {
            val cont = writeContinuation.getAndSet(null)
            if (cont != null) {
                cont.resume(Unit)
            }
        }

        suspend fun cancel() {
            job.cancel()
            job.join()
        }
    }

    class ErrorHttpHandlerContext(val cause: Throwable,
                                  internal: HttpHandlerContextInt) : HttpHandlerContext(internal)

    class RequestHttpHandlerContext(val request: HttpRequest,
                                    internal: HttpHandlerContextInt) : HttpHandlerContext(internal) {

        val params by lazy { QueryStringDecoder(request.uri()) }

        override val keepAlive
            get() = false //HttpUtil.isKeepAlive(request)

    }


    abstract class HttpHandlerContext(internal val internal: HttpHandlerContextInt) {

        private var done = false
        private var responded = false
        private var sendLastContent = false

        val isActive
            get() = internal.isActive()

        protected open val keepAlive = false

        suspend fun response(response: HttpResponse) {
            if (keepAlive) {
                response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE)
            }

            internal.replyFunc(response)
            sendLastContent = response !is FullHttpResponse
            responded = true
        }

        suspend fun response(html: String = "",
                             contentType: String = "text/html",
                             charset: Charset = Charset.forName("UTF-8"),
                             status: HttpResponseStatus = OK) {


            val data = copiedBuffer(html, charset)

            val response = DefaultFullHttpResponse(HTTP_1_1, status, data)

            with(response.headers()) {
                set(CONTENT_TYPE, contentType)
                set(CONTENT_LENGTH, data.writerIndex())
            }

            response(response)
        }

        suspend fun jsonResponse(block: JsonContext.() -> Unit) {
            val str = StringBuilder()
            JsonContext(str).block()
            response(str.toString(), "application/json")
        }

        suspend fun content(response: HttpContent) {
            if (!responded) {
                throw RuntimeException("no response send before data");
            }
            internal.replyFunc(response)
        }

        suspend fun content(response: ByteBuf) {
            content(DefaultHttpContent(response))
        }

        suspend fun end() {
            if (done) {
                return
            }
            done = true
            if (!responded) {
                val notFoundResponse = DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND)
                notFoundResponse.headers().setInt(CONTENT_LENGTH, 0)
                response(notFoundResponse)
                sendLastContent = false
            }
            if (sendLastContent) {
                internal.replyFunc(LastHttpContent.EMPTY_LAST_CONTENT)
            }
        }

    }

    class WebSocketHandlerContext(val request: WebSocketFrame,
                                  val isActive: () -> Boolean,
                                  private val replyFunc: (WebSocketFrame) -> Unit) {

        var ended = false

        fun response(response: WebSocketFrame) {
            replyFunc(response)
        }

        fun response(response: String) {
            replyFunc(TextWebSocketFrame(response))
        }

        fun response(response: ByteBuf) {
            replyFunc(BinaryWebSocketFrame(response))
        }
    }

}

class RouteContext(private val matcher: Matcher) {
    val regexGroups = object : AbstractList<String>() {
        override fun get(index: Int): String = matcher.group(index)

        override val size: Int
            get() = matcher.groupCount() + 1
    }
}

suspend fun HttpServer.RequestHttpHandlerContext.route(regexp: String,
                                                                                method: HttpMethod? = null,
                                                                                methods: MutableList<HttpMethod> = mutableListOf(HttpMethod.GET),
                                                                                block: suspend RouteContext.() -> Unit) {
    val matcher = Pattern.compile(regexp).matcher(params.path())
    if (method != null) {
        methods += method
    }
    if (matcher.matches() && methods.contains(request.method())) {
        RouteContext(matcher).block()
    }
}


suspend fun HttpServer.HttpHandlerContext.staticResourcesHandler(path: String, resourcesBase: String) {
    val resource = this.javaClass.classLoader.getResource(resourcesBase + "/" + path)
    if (resource == null) {
        response(DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND))
        return
    }

    val response = DefaultHttpResponse(HTTP_1_1, OK)
    with(response.headers()) {
        set(CONTENT_TYPE, "text/html")
    }
    response(response)

    resource.openStream().use {
        val bytes = ByteArray(512 * 1024)
        while (isActive) {
            val r = it.read(bytes)
            if (r <= 0) {
                break
            }
            content(copiedBuffer(bytes, 0, r))
        }
    }
}
