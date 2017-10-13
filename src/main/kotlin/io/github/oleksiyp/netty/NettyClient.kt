package io.github.oleksiyp.netty

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.cancelFutureOnCompletion
import kotlinx.coroutines.experimental.suspendCancellableCoroutine

fun NettyClient() = NettyClient(ByteBuf::class.java)

class NettyClient<I>(cls: Class<I>) : NettyBase() {

    val bootstrap = Bootstrap()
            .group(NioEventLoopGroup())
            .channel(NioSocketChannel::class.java)

    suspend fun connect(host: String,
                        port: Int): CoroutineHandler<I> =
            suspendCancellableCoroutine { cont ->
                val future = bootstrap.connect(host, port)
                cont.cancelFutureOnCompletion(future)
                future.addListener {
                    val channel = future.channel()
                    val handler = channel.attr(CoroutineHandler.attribute<I>()).get()
                    cont.resume(handler)
                }
            }

    override val coroutineContext = bootstrap.config().group().asCoroutineDispatcher()

    init {
        bootstrap.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val pipeline = ch.pipeline()

                pipeline.addLast(Handler(cls) {
                    newHandlerContext(ch)
                })
            }
        })
    }

}