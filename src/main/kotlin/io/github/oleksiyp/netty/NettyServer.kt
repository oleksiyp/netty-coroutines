package io.github.oleksiyp.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import kotlinx.coroutines.experimental.asCoroutineDispatcher

class NettyServer(port: Int,
                  private val pipelineBuilder: PipelineBuilderScope.(server: NettyServer) -> Unit) : NettyBase() {


    val bootstrap = ServerBootstrap()
            .group(NioEventLoopGroup(), NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)


    override val coroutineContext = bootstrap.config().childGroup().asCoroutineDispatcher()

    init {
        bootstrap.childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()
                PipelineBuilderScope(pipeline).pipelineBuilder(this@NettyServer)
            }
        })
        bootstrap.bind(port).sync()
    }

    inner class PipelineBuilderScope(val pipeline: ChannelPipeline) {
        fun <I> ChannelPipeline.addCoroutineHandler(cls : Class<I>,
                                                    requestHandler: suspend CoroutineHandler<I>.() -> Unit) {
            addLast(Handler(cls) {
                newHandlerContext(channel(), requestHandler)
            })
        }

        fun ChannelPipeline.addCoroutineHandler(requestHandler: suspend CoroutineHandler<ByteBuf>.() -> Unit) {
            addCoroutineHandler(ByteBuf::class.java, requestHandler)
        }
    }

}

