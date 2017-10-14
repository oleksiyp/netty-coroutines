package io.github.oleksiyp.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import kotlinx.coroutines.experimental.asCoroutineDispatcher

open class NettyServer(port: Int,
                       pipelineBuilder: PipelineBuilderScope.() -> Unit) {


    val bootstrap = ServerBootstrap()
            .group(NioEventLoopGroup(), NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)


    init {
        bootstrap.childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()
                val nettyDispatcher = bootstrap.config().childGroup().asCoroutineDispatcher()
                PipelineBuilderScope(pipeline, nettyDispatcher).pipelineBuilder()
            }
        })
        bootstrap.bind(port).sync()
    }

}

