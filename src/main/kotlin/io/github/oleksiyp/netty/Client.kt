package io.github.oleksiyp.netty

import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.*

class Client<I>() {

    val bootstrap = Bootstrap()
            .group(NioEventLoopGroup())
            .channel(NioSocketChannel::class.java)

    fun connect(host: String,
                port: Int): HandlerContext<I> {
        val channel = bootstrap.connect(host, port).sync().channel()
        return channel.attr(HandlerContext.attribute<I>()).get()
    }

    protected val coroutineContext = bootstrap.config().group().asCoroutineDispatcher()

    init {
        bootstrap.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()

                pipeline.addLast(LoggingHandler(LogLevel.INFO))

                val internal = HandlerContext.Internal<I>()
                val handlerCtx = HandlerContext(internal)

                val job = launch(coroutineContext) {
                    internal.isActive = this::isActive
                    internal.isWriteable = { ch.isWritable() }
                    internal.init(System.out::println)

                    while (isActive) {
                        val byteBuf = internal.sendChannel.receive()
                        ch.writeAndFlush(byteBuf)
                    }

                    internal.cancel()
                }
                internal.job = job

                ch.attr(HandlerContext.attribute<I>()).set(handlerCtx)

                pipeline.addLast(object : SimpleChannelInboundHandler<I>() {

                    override fun channelRead0(ctx: ChannelHandlerContext, msg: I) {
                        ReferenceCountUtil.retain(msg)
                        ctx.handlerContext<I>()?.let {
                            val continueRead = it.internal.dataReceived(msg)
                            println("C: $continueRead")
                            val chCfg = ctx.channel().config()
                            if (chCfg.isAutoRead != continueRead) {
                                chCfg.setAutoRead(continueRead)
                            }
                        }
                    }


                    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
                        if (ctx.channel().isWritable) {
                            ctx.handlerContext<I>()?.internal?.resumeWrite()
                        }
                    }

                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        runBlocking(coroutineContext) {
                            ctx.handlerContext<I>()?.internal?.cancel()
                        }
                    }
                })
            }
        })
    }


}