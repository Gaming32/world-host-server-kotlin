package io.github.gaming32.worldhostserver

import io.ktor.server.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.util.*

data class Connection(
    val id: UUID,
    val address: InetAddress,
    val userUuid: UUID,
    val session: WebSocketServerSession,
    var open: Boolean = true
)

class ConnectionSetSync {
    private val connections = mutableMapOf<UUID, Connection>()
    private val connectionsByUserId = mutableMapOf<UUID, MutableList<Connection>>()

    fun byId(id: UUID) = connections[id]

    fun byUserId(userId: UUID) = connectionsByUserId[userId] ?: listOf()

    fun add(connection: Connection) {
        connections[connection.id] = connection
        connectionsByUserId.getOrPut(connection.userUuid) { mutableListOf() }.add(connection)
    }

    fun remove(connection: Connection) {
        connections.remove(connection.id)
        connectionsByUserId[connection.id]?.let {
            it.remove(connection)
            if (it.isEmpty()) {
                connectionsByUserId.remove(connection.id)
            }
        }
    }
}

class ConnectionSetAsync {
    private val lock = Mutex()
    private val sync = ConnectionSetSync()

    suspend fun byId(id: UUID) = lock.withLock { sync.byId(id) }

    suspend fun byUserId(userId: UUID) = lock.withLock { sync.byUserId(userId) }

    suspend fun add(connection: Connection) = lock.withLock { sync.add(connection) }

    suspend fun remove(connection: Connection) = lock.withLock { sync.remove(connection) }
}
