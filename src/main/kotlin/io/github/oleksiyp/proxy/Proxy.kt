package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.NettyServer
import io.github.oleksiyp.netty.route
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.experimental.runBlocking

class Proxy {
    val connections = mutableListOf<ProxyConnection>()

    init {
        NettyServer(5555) {
            pipeline.addServerHttpCodec()
            pipeline.addWebSocketHandler {
                route("/proxy/(.+)/log") {
                    val connection = getConnection(regexGroups[1].toInt())
                    val unsubscribe = connection.log.subscribe {
                        runBlocking {
                            send(it)
                        }
                    }
                    try {
                        while (isActive) {
                            receive()
                        }
                    } finally {
                        unsubscribe()
                    }
                }
            }
            pipeline.addHttpHandler {
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

                    val connection = getConnection(listenPort)

                    connection.stop()
                    connections.remove(connection)

                    response("Stopped")
                }
                route("/proxy/(.+)/log") {
                    val listenPort = regexGroups[1].toInt()

                    val connection = getConnection(listenPort)

                    val log = connection.log.toString().replace("\n", "</br>")

                    response("""
                        |<html>
                        |   <head>
                        |   <script>
                        |       var protocolPrefix = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
                        |       var connection = new WebSocket(protocolPrefix + '//' + location.host + '/proxy/$listenPort/log');
                        |       connection.onerror = function (error) {
                        |           console.log('WebSocket Error', error);
                        |       };
                        |       connection.onmessage = function (e) {
                        |           var log = document.getElementById("log")
                        |           log.appendChild(document.createTextNode(e.data));
                        |           log.appendChild(document.createElement("br"));
                        |       };
                        |
                        |   </script>
                        |   </head>
                        |   <body>
                        |       <pre id="log" />
                        |   </body>
                        |</html>
                    """.trimMargin())
                }
            }
            pipeline.addErrorHttpHandler {
                response("Error: " + cause.message!!, status = HttpResponseStatus.BAD_REQUEST)
            }
        }

        listen(5556, "localhost", 22)
    }

    private fun getConnection(listenPort: Int): ProxyConnection =
            connections.firstOrNull {
                it.listenPort == listenPort
            } ?: throw RuntimeException("not listeneing to " + listenPort)

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

