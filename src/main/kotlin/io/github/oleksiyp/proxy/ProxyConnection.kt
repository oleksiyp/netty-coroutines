package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.*
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.experimental.launch
import java.io.IOException

class ProxyConnection(val listenPort: Int,
                      val connectHost: String,
                      val connectPort: Int) {

    private fun pumpJob(input: CoroutineHandler<*>, output: CoroutineHandler<*>) = launch {
        while (isActive) {
            output.send(input.receive()!!)
        }
    }

    val client = NettyClient()

    val server = NettyServer(listenPort) {
        pipeline.addCoroutineHandler {
            val clientCtx = try {
                client.connect(connectHost, connectPort)
            } catch (x: IOException) {
                return@addCoroutineHandler
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
        val cfg = server.bootstrap.config()
        cfg.childGroup().shutdownGracefully()
        cfg.group().shutdownGracefully()

        val clCfg = client.bootstrap.config()
        clCfg.group().shutdownGracefully()
    }
}