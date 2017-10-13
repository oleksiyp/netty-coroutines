package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.*
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.experimental.cancelAndJoin
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class ProxyConnection(val listenPort: Int,
                      val connectHost: String,
                      val connectPort: Int) {


    private fun pumpJob(input: NettyScope<ByteBuf>, output: NettyScope<*>, counter: AtomicInteger) = launch {
        while (isActive) {
            val buf = input.receive()
            val bufSz = buf.writerIndex() - buf.readerIndex()
            counter.addAndGet(bufSz)
            output.send(buf)
        }
    }

    val log = StringBuffer()

    fun log(msg: String) {
        log.append(Date()).append(": ").append(msg).append("\n")
        println(msg)
    }

    val client = NettyClient()

    val server = NettyServer(listenPort) {
        pipeline.addCoroutineHandler {
            val clientCtx = try {
                client.connect(connectHost, connectPort)
            } catch (x: IOException) {
                return@addCoroutineHandler
            }

            val inbound = AtomicInteger()
            val outbound = AtomicInteger()

            val transferredLogger = transferredLoggingJob(inbound, outbound, 1000)


            val c2s = pumpJob(clientCtx, this, inbound)
            val s2c = pumpJob(this, clientCtx, outbound)

            listOf(s2c, c2s).mutualClose()
            listOf(clientCtx, this).mutualCloseJobs()

            s2c.join()
            c2s.join()

            transferredLogger.cancelAndJoin()
        }
    }

    private suspend fun transferredLoggingJob(inbound: AtomicInteger,
                                              outbound: AtomicInteger,
                                              delay: Long) = launch {

        class TransferredDiff {
            var transferred = 0

            fun update(newVal: Int) =
                    if (newVal > transferred) {
                        transferred = newVal
                        true
                    } else {
                        false
                    }

        }

        val inboundTransferred = TransferredDiff()
        val outboundTransferred = TransferredDiff()

        while (isActive) {
            delay(delay)
            if (inboundTransferred.update(inbound.get())
                    || outboundTransferred.update(outbound.get())) {
                log("Transferred " +
                        "inbound: ${inboundTransferred.transferred} " +
                        "outbound: ${outboundTransferred.transferred}")
            }
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