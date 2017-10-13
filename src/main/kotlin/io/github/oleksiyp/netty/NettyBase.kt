package io.github.oleksiyp.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.suspendCancellableCoroutine

abstract class NettyBase {
    protected abstract val coroutineContext: CoroutineDispatcher

    protected fun <I> newHandlerContext(ch: Channel,
                                        requestHandler: (suspend CoroutineHandler<I>.() -> Unit)? = null): CoroutineHandler<I> {
        val internal = CoroutineHandler.Internal<I>()
        val handlerCtx = CoroutineHandler(internal)

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
                    handlerCtx.requestHandler()
                }
            } finally {
                internal.cancel()
                ch.close()
            }
        }
        return handlerCtx
    }

    protected inner class Handler<I>(cls : Class<I>,
                                     private val contextFactory: () -> CoroutineHandler<I>) : SimpleChannelInboundHandler<I>(cls) {

        override fun channelRegistered(ctx: ChannelHandlerContext) {
            ctx.setCoroutineHandler(contextFactory())
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: I) {
            ReferenceCountUtil.retain(msg)
            val handlerCtx = ctx.coroutineHandler<I>()
            handlerCtx.internal.dataReceived(msg)
        }

        override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
            if (ctx.channel().isWritable) {
                ctx.coroutineHandler<I>().internal.resumeWrite()
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            runBlocking(coroutineContext) {
                ctx.coroutineHandler<I>().internal.cancel()
                ctx.setCoroutineHandler(contextFactory())
            }
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext) {
            ctx.setCoroutineHandler<I>(null)
        }
    }
}