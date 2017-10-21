package io.github.oleksiyp.proxy.service

class ProxyOpsImpl : ProxyOps {
    val connections = mutableListOf<ProxyConnection>()

    fun newConnection(listenPort: Int,
                      connectHost: String,
                      connectPort: Int) : ProxyConnection = ProxyConnectionImpl(listenPort, connectHost, connectPort)


    override fun listen(listenPort: Int,
                        connectHost: String,
                        connectPort: Int): ProxyConnection {

        val connection = newConnection(listenPort, connectHost, connectPort)
        connections.add(connection)
        return connection
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
