package io.github.oleksiyp.netty

import io.kotlintest.experimental.mockk.*
import io.kotlintest.specs.StringSpec
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import org.junit.runner.RunWith

@RunWith(MockKJUnitRunner::class)
class NettyCoroutineHandlerTest : StringSpec({

    "bla bla" {
        val rh = mockk<(suspend NettyScope<Any>.() -> Unit)?>()
        val handler = spyk(NettyCoroutineHandler<Any>(requestHandler = rh))
        val ctx = mockk<ChannelHandlerContext>()
        val scope = mockk<NettyScope<Any>?>()
        val channel = mockk<Channel>()

        every { handler.newNettyScope(any()) } returns scope
        every { ctx.channel().attr(any<AttributeKey<Any>>()).set(any()) } answers { nothing }

        handler.channelRegistered(ctx)

        verify { handler.newNettyScope(any()) }
        verify { ctx.channel().attr(any<AttributeKey<Any>>()).set(refEq(scope)) }
    }
})