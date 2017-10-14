package io.github.oleksiyp.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import io.netty.util.internal.logging.InternalLogLevel
import io.netty.util.internal.logging.InternalLoggerFactory
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.LinkedListChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NettyCoroutineHandler<I>(cls: Class<I>,
                               private val dispatcher: CoroutineDispatcher = DefaultDispatcher,
                               private val attribute: AttributeKey<NettyScope<I>> = AttributeKey.newInstance<NettyScope<I>>("COROUTINE_HANDLER_" + Math.random()),
                               private val requestHandler: (suspend NettyScope<I>.() -> Unit)? = null)
    : SimpleChannelInboundHandler<I>(cls) {

    private val logger = InternalLoggerFactory.getInstance(NettyCoroutineHandler::class.java)

    fun ChannelHandlerContext.scopeAttr() = channel().attr(attribute)

    override fun channelRegistered(ctx: ChannelHandlerContext) {
        ctx.scopeAttr().set(newNettyScope(ctx.channel()))
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        val attr = ctx.scopeAttr()
        if (attr.get() == null) {
            attr.set(newNettyScope(ctx.channel()))
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: I) {
        ReferenceCountUtil.retain(msg)
        ctx.scopeAttr().get().internal.dataReceived(msg)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.log(InternalLogLevel.ERROR, "Error happened: ", cause)
        ctx.channel().close()
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable) {
            ctx.scopeAttr().get().internal.resumeWrite()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val previousScope = ctx.scopeAttr().getAndSet(null)
        previousScope?.let {
            runBlocking {
                it.internal.cancelJob()
            }
        }
    }

    fun newNettyScope(ch: Channel): NettyScope<I> {
        val internal = NettyScope.Internal<I>(ch, dispatcher)
        val handlerCtx = NettyScope(internal)

        internal.go {
            try {
                if (requestHandler == null) {
                    suspendCancellableCoroutine<Unit> { }
                } else {
                    requestHandler.let {
                        handlerCtx.it()
                    }
                }
                internal.notifyCloseHandlers()
                ch.close()
            } catch (ex: JobCancellationException) {
                internal.notifyCloseHandlers()
                ch.close()
            } catch (ex: Exception) {
                ch.pipeline().fireExceptionCaught(ex)
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

    class Internal<I>(private val ch: Channel,
                      private val dispatcher: CoroutineDispatcher,
                      private val writeability: Boolean = true) {

        lateinit var job: Job
        fun isActive() = job.isActive

        fun readabilityChanged(newValue: Boolean) {
            val chCfg = ch.config()
            if (chCfg.isAutoRead != newValue) {
                chCfg.isAutoRead = newValue
            }
        }

        fun write(msg: Any) = ch.writeAndFlush(msg)

        fun isWritable() = if (writeability) ch.isWritable else true

        val onCloseHandlers = mutableListOf<suspend () -> Unit>()

        private val readabilityBarrier = ReadabilityBarrier(10)
        private val writeContinuation = AtomicReference<CancellableContinuation<Unit>>()
        private val receiveChannel = LinkedListChannel<I>()

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
            private val nNonReadable = AtomicInteger()
            fun changeReadability(readability: Boolean) {
                if (readability) {
                    readabilityChanged(true)
                    nNonReadable.set(0)
                } else {
                    if (nNonReadable.incrementAndGet() > threshold) {
                        readabilityChanged(false)
                    }
                }
            }
        }

        suspend fun send(msg: Any) {
            if (!isWritable()) {
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

        suspend fun notifyCloseHandlers() {
            for (handler in onCloseHandlers) {
                handler()
            }
        }

        suspend fun cancelJob() {
            job.cancel()
            job.join()
        }

        fun go(block: suspend () -> Unit) {
            val q = ArrayBlockingQueue<CancellableContinuation<Unit>>(1)
            job = launch(dispatcher) {
                suspendCancellableCoroutine<Unit> { cont -> q.put(cont) }
                // after this line job is assigned
                block()
            }
            val cont = q.take()
            cont.resume(Unit)
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


suspend fun List<NettyScope<*>>.scopesMutualClose() {
    map { it.internal.job }.mutualClose()
}
