package io.github.oleksiyp.netty

import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.cancelFutureOnCompletion
import kotlinx.coroutines.experimental.channels.LinkedListChannel
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoroutineHandler<I>(internal val internal: Internal<I>) {
    val isActive
        get() = internal.isActive()

    suspend fun receive() : I = internal.receive()

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
        lateinit var readabilityChanged : (Boolean) -> Unit
        lateinit var write: (msg: Any) -> ChannelFuture

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


        suspend fun cancel() {
            job.cancel()
        }


    }

    companion object {
        val ATTRIBUTE = AttributeKey.newInstance<CoroutineHandler<*>>("HANDLER_CONTEXT")

        fun <T>attribute() = ATTRIBUTE as AttributeKey<CoroutineHandler<T>>
    }


}

fun <I> ChannelHandlerContext.coroutineHandler() =
        channel().attr(CoroutineHandler.attribute<I>()).get()

fun <I> ChannelHandlerContext.setCoroutineHandler(handlerCtx: CoroutineHandler<I>?) =
        channel().attr(CoroutineHandler.attribute<I>()).set(handlerCtx)

fun <I> io.netty.channel.Channel.setCoroutineHandler(handlerCtx: CoroutineHandler<I>) =
        attr(CoroutineHandler.attribute<I>()).set(handlerCtx)

suspend fun List<Job>.mutualClose() {
    forEach {
        it.invokeOnCompletion {
            forEach { c -> if (c != it) c.cancel() }
        }
    }
}


suspend fun List<CoroutineHandler<*>>.mutualCloseJobs() {
    map { it.internal.job }.mutualClose()
}
