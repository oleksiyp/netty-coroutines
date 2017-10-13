package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.*
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.experimental.launch
import java.io.IOException

class ProxyConnection(val listenPort: Int,
                      val connectHost: String,
                      val connectPort: Int) {

    private fun pumpJob(input: HandlerContext<*>, output: HandlerContext<*>) = launch {
        while (isActive) {
            output.send(input.receive()!!)
        }
    }

    val client = Client<ByteBuf>()

    val server = Server(listenPort) {
        pipeline.addHandler<ByteBuf> {
            val clientCtx = try {
                client.connect(connectHost, connectPort)
            } catch (x: IOException) {
                return@addHandler
            }

            val c2s = pumpJob(clientCtx, this)
            val s2c = pumpJob(this, clientCtx)

            listOf(s2c, c2s).mutualClose()
            listOf(clientCtx, this).mutualCloseJobs()

            s2c.join()
            c2s.join()
        }
    }

    fun stop() {
        server.bootstrap.config().childGroup().shutdownGracefully()
        server.bootstrap.config().group().shutdownGracefully()
        client.bootstrap.config().group().shutdownGracefully()
    }
}