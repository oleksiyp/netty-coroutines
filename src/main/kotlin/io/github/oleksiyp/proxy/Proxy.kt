package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.*
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.experimental.launch
import java.io.IOException

class Proxy {
    val connections = mutableListOf<ProxyConnection>()

    init {
        HttpServer(5555) {
            pipeline.addHttpHandler({
                route("/proxy/listen/(.+)") {
                    val listenPort = regexGroups[1].toInt()

                    val connectPort = params.firstIntParam("port")
                            ?: throw RuntimeException("port not specified")

                    val connectHost = params.firstParam("host")
                            ?: throw RuntimeException("host not specified")

                    listen(listenPort, connectHost, connectPort)

                    response("Done")
                }
                route("/proxy/all") {
                    jsonResponse {
                        seq {
                            connections.forEach {
                                hash {
                                    "listenPort"..{ num(it.listenPort) }
                                    "connectHost"..{ str(it.connectHost) }
                                    "connectPort"..{ num(it.connectPort) }
                                }
                            }
                        }
                    }
                }
                route("/proxy/stop/(.+)") {
                    val listenPort = regexGroups[1].toInt()

                    val connection = connections.firstOrNull {
                        it.listenPort == listenPort
                    } ?: throw RuntimeException("not listeneing to " + listenPort)

                    connection.stop()
                    connections.remove(connection)

                    response("Stopped")
                }
            })
            pipeline.addErrorHandler {
                response("Error: " + cause.message!!, status = HttpResponseStatus.BAD_REQUEST)
            }
        }
    }

    private fun listen(listenPort: Int,
                       connectHost: String,
                       connectPort: Int) {

        connections.add(ProxyConnection(listenPort, connectHost, connectPort))
    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Proxy()
        }
    }
}

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
