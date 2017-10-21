package io.github.oleksiyp.netty

import io.kotlintest.experimental.mockk.MockKJUnitRunner
import io.kotlintest.experimental.mockk.every
import io.kotlintest.experimental.mockk.mockk
import io.kotlintest.experimental.mockk.spyk
import io.kotlintest.specs.StringSpec
import io.netty.channel.ChannelHandlerContext
import org.junit.runner.RunWith

@RunWith(MockKJUnitRunner::class)
class NettyCoroutineHandlerTest : StringSpec({

    "bla bla" {
        val rh = mockk<(suspend NettyScope<Any>.() -> Unit)?>()
        val handler = spyk(NettyCoroutineHandler<Any>(requestHandler = rh))
        val ctx = mockk<ChannelHandlerContext>()
        val scope = mockk<NettyScope<Any>?>()

        every { handler.newNettyScope(any()) } returns scope

        handler.channelRegistered(ctx)


    }

})