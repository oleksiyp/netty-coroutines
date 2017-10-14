package io.github.oleksiyp.proxy.service

interface Proxy {
    fun getConnection(port: Int): ProxyConnection

    fun listen(listenPort: Int, connectHost: String, connectPort: Int)

    fun allConnections(): List<ProxyConnection>

    fun unlisten(listenPort: Int): ProxyConnection

}