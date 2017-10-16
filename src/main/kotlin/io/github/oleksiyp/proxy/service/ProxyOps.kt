package io.github.oleksiyp.proxy.service

interface ProxyOps {
    fun getConnection(port: Int): ProxyConnection

    fun listen(listenPort: Int, connectHost: String, connectPort: Int): ProxyConnection

    fun allConnections(): List<ProxyConnection>

    fun unlisten(listenPort: Int): ProxyConnection

}