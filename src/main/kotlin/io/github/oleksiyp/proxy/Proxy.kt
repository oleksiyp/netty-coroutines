package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.HttpServer
import io.github.oleksiyp.netty.firstIntParam
import io.github.oleksiyp.netty.firstParam
import io.github.oleksiyp.netty.route
import io.netty.handler.codec.http.HttpResponseStatus

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

        listen(5556, "localhost", 22)
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

