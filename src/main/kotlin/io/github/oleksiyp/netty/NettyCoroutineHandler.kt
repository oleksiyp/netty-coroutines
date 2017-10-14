package io.github.oleksiyp.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import io.netty.util.internal.logging.InternalLogLevel
import io.netty.util.internal.logging.InternalLoggerFactory
import kotlinx.coroutines.experimental.*

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

    fun newNettyScope(channel: Channel): NettyScope<I> {
        val internal = NettyScope.Internal<I>(channel, dispatcher)
        val handlerCtx = NettyScope(internal)

        internal.go {
            try {
                if (requestHandler == null) {
                    pauseTillCancel()
                } else {
                    requestHandler.invoke(handlerCtx)
                }
                internal.notifyCloseHandlers()
                channel.close()
            } catch (ex: JobCancellationException) {
                internal.notifyCloseHandlers()
                channel.close()
            } catch (ex: Exception) {
                channel.pipeline().fireExceptionCaught(ex)
            }
        }
        return handlerCtx
    }

    private suspend fun pauseTillCancel() = suspendCancellableCoroutine<Unit> { }
}


