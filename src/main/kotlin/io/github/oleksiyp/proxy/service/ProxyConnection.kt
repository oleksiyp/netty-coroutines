package io.github.oleksiyp.proxy.service

import io.github.oleksiyp.netty.*
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.experimental.cancelAndJoin
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class ProxyConnection(val listenPort: Int,
                      val connectHost: String,
                      val connectPort: Int) {

    val inbound = AtomicInteger()
    val outbound = AtomicInteger()

    private fun pumpJob(input: NettyScope<ByteBuf>, output: NettyScope<*>, counter: AtomicInteger) = launch {
        while (isActive) {
            val buf = input.receive()
            val bufSz = buf.writerIndex() - buf.readerIndex()
            counter.addAndGet(bufSz)
            output.send(buf)
        }
    }

    val log = Log()
    fun log(msg: String) = log.append("${Date()}: $msg")
    val transferredLogger = launch { transferredLoggingJob(inbound, outbound, 1000) }

    private val client = NettyClient()
    private val server = NettyServer(listenPort) {
        pipeline.addCoroutineHandler {
            log("Connecting $connectHost:$connectPort")
            val clientCtx = try {
                client.connect(connectHost, connectPort)
            } catch (ex: IOException) {
                log("Error connecting: ${ex.message}")
                return@addCoroutineHandler
            }
            log("Connected $connectHost:$connectPort")

            try {
                val c2s = pumpJob(clientCtx, this, inbound)
                val s2c = pumpJob(this, clientCtx, outbound)

                listOf(s2c, c2s).mutualClose()
                listOf(clientCtx, this).scopesMutualClose()

                s2c.join()
                c2s.join()
            } finally {
                log("Closing connection")
            }
        }
    }

    private suspend fun transferredLoggingJob(inbound: AtomicInteger,
                                              outbound: AtomicInteger,
                                              delay: Long) = launch {

        class TransferredDiff {
            var transferred = 0
            var lastDiff = 0

            fun update(newVal: Int) =
                    if (newVal > transferred) {
                        lastDiff = newVal - transferred
                        transferred = newVal
                        true
                    } else {
                        lastDiff = 0
                        false
                    }


        }

        val inboundTransferred = TransferredDiff()
        val outboundTransferred = TransferredDiff()

        while (isActive) {
            delay(delay)
            if (inboundTransferred.update(inbound.get())
                    or outboundTransferred.update(outbound.get())) {
                log("Transferred " +
                        "inbound: ${inboundTransferred.lastDiff} " +
                        "outbound: ${outboundTransferred.lastDiff}")
            }
        }
    }

    fun stop() {
        val cfg = server.bootstrap.config()
        cfg.childGroup().shutdownGracefully()
        cfg.group().shutdownGracefully()

        val clCfg = client.bootstrap.config()
        clCfg.group().shutdownGracefully()

        runBlocking {
            transferredLogger.cancelAndJoin()
        }
    }
}