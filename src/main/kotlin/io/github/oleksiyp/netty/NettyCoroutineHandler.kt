package io.github.oleksiyp.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.LinkedListChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicReference

class NettyCoroutineHandler<I>(cls: Class<I>,
                               private val coroutineContext: CoroutineDispatcher,
                               private val requestHandler: (suspend NettyScope<I>.() -> Unit)? = null,
                               private val attribute: AttributeKey<NettyScope<I>> = AttributeKey.newInstance<NettyScope<I>>("COROUTINE_HANDLER_" + Math.random()))
    : SimpleChannelInboundHandler<I>(cls) {


    fun ChannelHandlerContext.scope() =
            channel().attr(attribute).get()

    fun ChannelHandlerContext.setScope(handlerCtx: NettyScope<I>?) =
            channel().attr(attribute).set(handlerCtx)

    fun io.netty.channel.Channel.setScope(handlerCtx: NettyScope<I>) =
            attr(attribute).set(handlerCtx)


    override fun channelRegistered(ctx: ChannelHandlerContext) {
        ctx.setScope(newNettyScope(ctx.channel()))
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (ctx.scope() == null) {
            ctx.setScope(newNettyScope(ctx.channel()))
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: I) {
        ReferenceCountUtil.retain(msg)
        ctx.scope().internal.dataReceived(msg)
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable) {
            ctx.scope().internal.resumeWrite()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        runBlocking(coroutineContext) {
            ctx.scope().internal.close()
            ctx.setScope(null)
        }
    }

    fun newNettyScope(ch: Channel): NettyScope<I> {
        val internal = NettyScope.Internal<I>()
        val handlerCtx = NettyScope(internal)

        internal.job = launch(coroutineContext) {
            internal.isActive = this::isActive
            internal.isWriteable = ch::isWritable
            internal.readabilityChanged = {
                val chCfg = ch.config()
                if (chCfg.isAutoRead != it) {
                    chCfg.isAutoRead = it
                }
            }

            internal.write = { ch.writeAndFlush(it) }

            try {
                if (requestHandler == null) {
                    suspendCancellableCoroutine<Unit> { }
                } else {
                    requestHandler.let {
                        handlerCtx.it()
                    }
                }
            } catch (ex: JobCancellationException) {
                // skip
            } catch (ex: Exception) {
                ch.pipeline().fireExceptionCaught(ex)
            } finally {
                internal.close()
                ch.close()
            }
        }
        return handlerCtx
    }
}


open class NettyScope<I>(internal val internal: Internal<I>) {
    val isActive
        get() = internal.isActive()

    suspend fun receive(): I = internal.receive()
    suspend fun skipAllReceived() = internal.skipAllReceived()

    suspend fun receive(block: suspend (I) -> Unit) {
        val msg = receive()
        try {
            block.invoke(msg)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    suspend fun send(obj: Any) {
        internal.send(obj)
    }

    class Internal<I> {
        lateinit var isActive: () -> Boolean
        lateinit var isWriteable: () -> Boolean
        lateinit var job: Job
        lateinit var readabilityChanged: (Boolean) -> Unit
        lateinit var write: (msg: Any) -> ChannelFuture
        val onCloseHandlers = mutableListOf<suspend () -> Unit>()

        val readabilityBarrier = ReadabilityBarrier(10)

        val writeContinuation = AtomicReference<CancellableContinuation<Unit>>()

        var receiveChannel = LinkedListChannel<I>()

        fun dataReceived(msg: I) {
            readabilityBarrier.changeReadability(receiveChannel.isEmpty)
            receiveChannel.offer(msg)
        }

        suspend fun receive(): I {
            val data = receiveChannel.poll()
            if (data == null) {
                readabilityBarrier.changeReadability(true)
                return receiveChannel.receive()
            }
            readabilityBarrier.changeReadability(receiveChannel.isEmpty)
            return data
        }

        suspend fun skipAllReceived() {
            receiveChannel.close()
        }

        inner class ReadabilityBarrier(val threshold: Int) {
            private var nNonReadable = 0
            public fun changeReadability(readability: Boolean) {
                if (readability) {
                    readabilityChanged(true)
                    nNonReadable = 0
                } else {
                    nNonReadable++
                    if (nNonReadable > threshold) {
                        readabilityChanged(false)
                    }
                }
            }
        }

        suspend fun send(msg: Any) {
            if (!isWriteable()) {
                suspendCancellableCoroutine<Unit> { cont ->
                    writeContinuation.getAndSet(cont)?.resume(Unit)
                }
            }

            suspendCancellableCoroutine<Unit> { cont ->
                val future = write(msg)
                cont.cancelFutureOnCompletion(future)
                future.addListener {
                    if (future.isSuccess) {
                        cont.resume(Unit)
                    } else if (future.cause() is ClosedChannelException) {
                        job.cancel()
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(future.cause())
                    }
                }
            }
        }


        fun resumeWrite() {
            val cont = writeContinuation.getAndSet(null)
            if (cont != null) {
                cont.resume(Unit)
            }
        }


        suspend fun close() {
            job.cancel()
            job.join()
            for (handler in onCloseHandlers) {
                handler()
            }
        }


    }


}

suspend fun List<Job>.mutualClose() {
    forEach {
        it.invokeOnCompletion {
            forEach { c -> if (c != it) c.cancel() }
        }
    }
}


suspend fun List<NettyScope<*>>.jobsMutualClose() {
    map { it.internal.job }.mutualClose()
}
