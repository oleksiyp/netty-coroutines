package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.NettyServer
import io.github.oleksiyp.proxy.controller.AboutController
import io.github.oleksiyp.proxy.controller.ProxyController
import io.github.oleksiyp.proxy.service.ProxyImplementation
import io.netty.handler.codec.http.HttpResponseStatus


class ProxyApp {
    private val proxyOps = ProxyImplementation()
    private val controller = ProxyController(proxyOps) + AboutController()

    init {
        NettyServer(5555) {
            pipeline.addServerHttpCodec()
            pipeline.addServerWebSocketHandler(nettyDispatcher, requestHandler = controller.webSockHandler)
            pipeline.addServerErrorHttpHandler(nettyDispatcher) {
                response("Error: " + cause.message!!, status = HttpResponseStatus.BAD_REQUEST)
            }
            pipeline.addServerHttpHandler(nettyDispatcher, controller.httpHandler)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ProxyApp()
        }
    }
}