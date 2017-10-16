package io.github.oleksiyp.proxy.service

interface ProxyConnection {
    val listenPort: Int
    val connectHost: String
    val connectPort: Int

    val log: Log

    fun stop()
}