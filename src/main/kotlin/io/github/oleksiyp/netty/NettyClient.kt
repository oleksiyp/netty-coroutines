package io.github.oleksiyp.netty

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.AttributeKey
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.cancelFutureOnCompletion
import kotlinx.coroutines.experimental.suspendCancellableCoroutine

fun NettyClient(pipelineBuilder: ClientPipelineBuilderScope.() -> Unit = {}) = NettyClient(ByteBuf::class.java, pipelineBuilder)

class NettyClient<I>(cls: Class<I>,
                     private val pipelineBuilder: ClientPipelineBuilderScope.() -> Unit = {}) {
    val bootstrap = Bootstrap()
            .group(NioEventLoopGroup())
            .channel(NioSocketChannel::class.java)

    val attribute = AttributeKey.newInstance<NettyScope<I>>("CLIENT_COROUTINE_HANDLER_" + Math.random())

    suspend fun connect(host: String,
                        port: Int): NettyScope<I> =
            suspendCancellableCoroutine { cont ->
                val future = bootstrap.connect(host, port)
                cont.cancelFutureOnCompletion(future)
                future.addListener {
                    val channel = future.channel()
                    val handler = channel.attr(attribute).get()
                    if (future.isSuccess) {
                        cont.resume(handler)
                    } else {
                        cont.resumeWithException(future.cause())
                    }
                }
            }

    init {
        bootstrap.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()
                val nettyDispatcher = bootstrap.config().group().asCoroutineDispatcher()
                ClientPipelineBuilderScope(pipeline, nettyDispatcher).pipelineBuilder()
                pipeline.addLast(NettyCoroutineHandler(cls, attribute = attribute))
            }
        })
    }

}