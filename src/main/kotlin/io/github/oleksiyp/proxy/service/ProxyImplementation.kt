package io.github.oleksiyp.proxy.service

class ProxyImplementation : Proxy {
    val connections = mutableListOf<ProxyConnection>()

    override fun listen(listenPort: Int,
               connectHost: String,
               connectPort: Int) {

        connections.add(ProxyConnection(listenPort, connectHost, connectPort))
    }

    override fun getConnection(listenPort: Int): ProxyConnection {
        return connections.firstOrNull {
            it.listenPort == listenPort
        } ?: throw RuntimeException("not listeneing to " + listenPort)
    }

    override fun allConnections(): List<ProxyConnection> = connections

    override fun unlisten(listenPort: Int): ProxyConnection {
        val connection = getConnection(listenPort)
        connection.stop()
        connections.remove(connection)
        return connection
    }

}
