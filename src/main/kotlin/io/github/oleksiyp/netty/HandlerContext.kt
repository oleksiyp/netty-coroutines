package io.github.oleksiyp.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference

class HandlerContext<I>(internal val internal: Internal<I>) {
    val isActive
        get() = internal.isActive()

    suspend fun receive() : I = internal.receiveChannel.receive()

    suspend fun receive(block: suspend (I) -> Unit) {
        val msg = receive()
        try {
            block.invoke(msg)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    suspend fun send(obj: Any) {
        internal.reply(obj)
    }

    class Internal<I> {
        lateinit var isActive: () -> Boolean
        lateinit var isWriteable: () -> Boolean
        lateinit var job: Job

        @Volatile
        var readJob: Job? = null

        val writeContinuation = AtomicReference<CancellableContinuation<Unit>>()

        var receiveChannel = RendezvousChannel<I>()
        var sendChannel = RendezvousChannel<Any>()

        suspend fun reply(obj: Any) {
            if (isWriteable()) {
                sendChannel.send(obj)
            } else {
                suspendCancellableCoroutine<Unit> { cont ->
                    writeContinuation.getAndSet(cont)?.resume(Unit)
                }
                sendChannel.send(obj)
            }

        }


        fun resumeWrite() {
            val cont = writeContinuation.getAndSet(null)
            if (cont != null) {
                cont.resume(Unit)
            }
        }


        suspend fun cancel() {
            readJob?.let { it.cancel() }
            job.cancel()
        }

    }

    companion object {
        val ATTRIBUTE = AttributeKey.newInstance<HandlerContext<*>>("HANDLER_CONTEXT")

        fun <T>attribute() = ATTRIBUTE as AttributeKey<HandlerContext<T>>
    }


}

fun <I> ChannelHandlerContext.handlerContext() = channel().attr(HandlerContext.attribute<I>()).get()

suspend fun List<Job>.mutualClose() {
    forEach {
        it.invokeOnCompletion {
            forEach { c -> if (c != it) c.cancel() }
        }
    }
}


suspend fun List<HandlerContext<*>>.mutualCloseJobs() {
    map { it.internal.job }.mutualClose()
}
