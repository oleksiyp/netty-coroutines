package io.github.oleksiyp.netty

interface NettyController {
    val httpHandler: suspend RequestHttpHandlerScope.() -> Unit
    val webSockHandler: suspend WebSocketHandlerScope.() -> Unit

    operator fun plus(controller: DefaultNettyController): DefaultNettyController {
        return object : DefaultNettyController() {
            override val httpHandler: suspend RequestHttpHandlerScope.() -> Unit
                get() = {
                    this@NettyController.httpHandler(this)
                    controller.httpHandler(this)
                }

            override val webSockHandler: suspend WebSocketHandlerScope.() -> Unit
                get() = {
                    this@NettyController.webSockHandler(this)
                    controller.webSockHandler(this)
                }
        }
    }
}

abstract class DefaultNettyController : NettyController {
    override val httpHandler: suspend RequestHttpHandlerScope.() -> Unit = {}
    override val webSockHandler: suspend WebSocketHandlerScope.() -> Unit = {}
}