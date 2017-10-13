package io.github.oleksiyp.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.*

class Server(val port: Int,
             private val pipelineBuilder: PipelineBuilderScope.(server: Server) -> Unit) {


    val bootstrap = ServerBootstrap()
            .group(NioEventLoopGroup(), NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)


    protected val coroutineContext = bootstrap.config().childGroup().asCoroutineDispatcher()

    init {
        bootstrap.childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()
                pipeline.addLast(LoggingHandler(LogLevel.INFO))
                PipelineBuilderScope(pipeline).pipelineBuilder(this@Server)
            }
        })
        bootstrap.bind(port).sync()
    }


    inner class PipelineBuilderScope(val pipeline: ChannelPipeline) {
        fun <I>ChannelPipeline.addHandler(requestHandler: suspend HandlerContext<I>.() -> Unit) {
            addLast(object : SimpleChannelInboundHandler<I>() {

                override fun channelActive(ctx: ChannelHandlerContext) {
                    val internal = HandlerContext.Internal<I>()
                    val handlerCtx = HandlerContext(internal)
                    val channel = ctx.channel()

                    val job = launch(coroutineContext) {
                        internal.isActive = this::isActive
                        internal.isWriteable = channel::isWritable

                        val sendJob = launch {
                            while (isActive) {
                                val byteBuf = internal.sendChannel.receive()
                                channel.writeAndFlush(byteBuf)
                            }
                        }

                        try {
                            handlerCtx.requestHandler()
                        } finally {
                            internal.cancel()
                            sendJob.cancel()
                            sendJob.join()
                            channel.close()
                        }
                    }
                    internal.job = job

                    channel.attr(HandlerContext.ATTRIBUTE).set(handlerCtx)
                }

                override fun channelRead0(ctx: ChannelHandlerContext, msg: I) {
                    ReferenceCountUtil.retain(msg)
                    runBlocking {
                        val readJob = launch {
                            ctx.handlerContext<I>()?.internal?.receiveChannel?.send(msg)
                        }
                        ctx.handlerContext<I>().internal.readJob = readJob
                        readJob.join()
                        ctx.handlerContext<I>().internal.readJob = null
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
    }

}

