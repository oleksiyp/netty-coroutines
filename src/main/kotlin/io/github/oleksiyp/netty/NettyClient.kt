package io.github.oleksiyp.netty

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.AttributeKey
import kotlinx.coroutines.experimental.cancelFutureOnCompletion
import kotlinx.coroutines.experimental.suspendCancellableCoroutine

fun NettyClient() = NettyClient(ByteBuf::class.java)

class NettyClient<I>(cls: Class<I>) {
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
                    cont.resume(handler)
                }
            }

    init {
        bootstrap.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()

                pipeline.addLast(NettyCoroutineHandler(cls, attribute = attribute))
            }
        })
    }

}