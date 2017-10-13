package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.*
import io.netty.buffer.ByteBufUtil
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame

class Proxy {
    val connections = mutableListOf<ProxyConnection>()

    init {
        NettyServer(5555) {
            pipeline.addServerHttpCodec()
            pipeline.addWebSocketHandler {
                outer@ while (isActive) {
                    val frame = receive()
                    when(frame) {
                        is TextWebSocketFrame -> println(frame.text())
                        is BinaryWebSocketFrame -> {
                            val buf = StringBuilder()
                            ByteBufUtil.appendPrettyHexDump(buf, frame.content())
                            println(buf)
                        }
                        is CloseWebSocketFrame -> break@outer
                    }
                    send(frame)
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
                        |       var connection = new WebSocket(protocolPrefix + '//' + location.host + '/proxy/$listenPort/log-ws');
                        |       connection.onopen = function () {
                        |           connection.send('Ping');
                        |       };
                        |       connection.onerror = function (error) {
                        |           console.log('WebSocket Error', error);
                        |       };
                        |       connection.onmessage = function (e) {
                        |           console.log('Server: ' + e.data);
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

