package io.github.oleksiyp.proxy.controller

import io.github.oleksiyp.netty.DefaultNettyController
import io.github.oleksiyp.netty.RequestHttpHandlerScope
import io.github.oleksiyp.netty.route

class AboutController : DefaultNettyController() {
    override val httpHandler: suspend RequestHttpHandlerScope.() -> Unit
        get() = {
            route("/about") {
                response("Hi there!")
            }
        }
}