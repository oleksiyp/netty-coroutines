package io.github.oleksiyp.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
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
        internal.reply(obj)
    }

    class Internal<I> {
        lateinit var isActive: () -> Boolean
        lateinit var isWriteable: () -> Boolean
        lateinit var job: Job
        lateinit var readabilityChanged : (Boolean) -> Unit

        val writeContinuation = AtomicReference<CancellableContinuation<Unit>>()

        var receiveChannel = Channel<I>(Channel.UNLIMITED)
        var sendChannel = RendezvousChannel<Any>()

        fun dataReceived(msg: I) {
            val empty = receiveChannel.isEmpty
            readabilityChanged(empty)
            receiveChannel.offer(msg)
        }

        suspend fun reply(obj: Any) {
            if (isWriteable()) {
                sendChannel.send(obj)
            } else {
                println("SUSPEND")
                suspendCancellableCoroutine<Unit> { cont ->
                    writeContinuation.getAndSet(cont)?.resume(Unit)
                }
                println("RESUMED")
                sendChannel.send(obj)
            }

        }


        fun resumeWrite() {
            println("RESUME")
            val cont = writeContinuation.getAndSet(null)
            if (cont != null) {
                cont.resume(Unit)
            }
        }


        suspend fun cancel() {
            job.cancel()
        }

        suspend fun receive(): I {
            val data = receiveChannel.receive()
            readabilityChanged(receiveChannel.isEmpty)
            return data
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
