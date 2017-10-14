package io.github.oleksiyp.proxy.controller

import io.github.oleksiyp.netty.DefaultNettyController
import io.github.oleksiyp.netty.RequestHttpHandlerScope
import io.github.oleksiyp.netty.WebSocketHandlerScope
import io.github.oleksiyp.netty.route
import io.github.oleksiyp.proxy.service.Proxy
import kotlinx.coroutines.experimental.runBlocking

class ProxyController(val proxyOps: Proxy) : DefaultNettyController() {
    override val httpHandler: suspend RequestHttpHandlerScope.() -> Unit = {
        route("/proxy/listen/(.+)") {
            val listenPort = regexGroups[1].toInt()

            val connectPort = params.firstIntParam("port")
                    ?: throw RuntimeException("port not specified")

            val connectHost = params.firstParam("host")
                    ?: throw RuntimeException("host not specified")

            proxyOps.listen(listenPort, connectHost, connectPort)

            response("Done")
        }
        route("/proxy/all") {
            jsonResponse {
                seq {
                    proxyOps.allConnections().forEach {
                        hash {
                            "listenPort"..it.listenPort
                            "connectHost"..it.connectHost
                            "connectPort"..it.connectPort
                        }
                    }
                }
            }
        }
        route("/proxy/stop/(.+)") {
            val listenPort = regexGroups[1].toInt()


            proxyOps.unlisten(listenPort)

            response("Stopped")
        }
        route("/proxy/(.+)/log") {
            val listenPort = regexGroups[1].toInt()

            proxyOps.getConnection(listenPort)

            response("""
                        |<html>
                        |   <head>
                        |   <script>
                        |       function append(text) {
                        |           var log = document.getElementById("log")
                        |           log.appendChild(document.createTextNode(text));
                        |           log.appendChild(document.createElement("br"));
                        |           window.scrollBy(0, log.scrollHeight);
                        |       }
                        |       var protocolPrefix = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
                        |       var connection = new WebSocket(protocolPrefix + '//' + location.host + '/proxy/$listenPort/log');
                        |       connection.onerror = function (error) {
                        |           console.log('WebSocket Error', error);
                        |       };
                        |       connection.onmessage = function (e) {
                        |           append(e.data)
                        |       };
                        |       connection.onclose = function(event) {
                        |           append("CLOSED")
                        |       }
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


    override val webSockHandler: suspend WebSocketHandlerScope.() -> Unit = {
        route("/proxy/(.+)/log") {
            val connection = proxyOps.getConnection(regexGroups[1].toInt())
            val closable = connection.log.subscribe { msg ->
                runBlocking {
                    send(msg)
                }
            }

            closable.use {
                while (isActive) {
                    receive()
                }
            }
        }
    }

}

