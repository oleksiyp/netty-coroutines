package io.github.oleksiyp.proxy.controller

import io.github.oleksiyp.mockk.MockKJUnitRunner
import io.github.oleksiyp.mockk.mockk
import io.github.oleksiyp.mockk.on
import io.github.oleksiyp.mockk.verify
import io.github.oleksiyp.netty.RequestHttpHandlerScope
import io.github.oleksiyp.proxy.service.ProxyOps
import io.kotlintest.specs.StringSpec
import io.netty.handler.codec.http.HttpMethod
import kotlinx.coroutines.experimental.runBlocking
import org.junit.runner.RunWith

@RunWith(MockKJUnitRunner::class)
class ProxyControllerTest : StringSpec() {
    init {
        val ops = mockk<ProxyOps>()
        val scope = mockk<RequestHttpHandlerScope>()
        val controller = ProxyController(ops)


        "httpHandler for /proxy/PORT/log should return response" {
            on { scope.params.path() } doReturn "/proxy/555/log"
            on { scope.request.method() } doReturn HttpMethod.GET

            runBlocking {
                controller.httpHandler(scope)

            }
            verify {
                scope.response("<html>\n" +
                        "   <head>\n" +
                        "   <script>\n" +
                        "       function append(text) {\n" +
                        "           var log = document.getElementById(\"log\")\n" +
                        "           log.appendChild(document.createTextNode(text));\n" +
                        "           log.appendChild(document.createElement(\"br\"));\n" +
                        "           window.scrollBy(0, log.scrollHeight);\n" +
                        "       }\n" +
                        "       var protocolPrefix = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';\n" +
                        "       var connection = new WebSocket(protocolPrefix + '//' + location.host + '/proxy/555/log');\n" +
                        "       connection.onerror = function (error) {\n" +
                        "           console.log('WebSocket Error', error);\n" +
                        "       };\n" +
                        "       connection.onmessage = function (e) {\n" +
                        "           append(e.data)\n" +
                        "       };\n" +
                        "       connection.onclose = function(event) {\n" +
                        "           append(\"CLOSED\")\n" +
                        "       }\n" +
                        "\n" +
                        "   </script>\n" +
                        "   </head>\n" +
                        "   <body>\n" +
                        "       <pre id=\"log\" />\n" +
                        "   </body>\n" +
                        "</html>")
            }
        }
    }

}

