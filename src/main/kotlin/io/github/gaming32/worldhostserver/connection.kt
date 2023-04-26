package io.github.gaming32.worldhostserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

data class Connection(
    val id: ConnectionId,
    val address: String,
    val userUuid: UUID,
    val socket: SocketWrapper,
    var country: Country? = null,
    var externalProxy: ExternalProxy? = null,
    var open: Boolean = true
) {
    constructor(ids: IdsPair, address: String, session: SocketWrapper) :
        this(ids.connectionId, address, ids.userId, session)

    override fun toString(): String {
        return "Connection(id=$id, address=$address, userUuid=$userUuid)"
    }
}

class ConnectionSetSync {
    @PublishedApi
    internal val connections = mutableMapOf<ConnectionId, Connection>()
    private val connectionsByUserId = mutableMapOf<UUID, MutableList<Connection>>()

    fun byId(id: ConnectionId) = connections[id]

    fun byUserId(userId: UUID) = connectionsByUserId[userId] ?: listOf()

    fun add(connection: Connection): Connection? {
        val old = connections.put(connection.id, connection)
        connectionsByUserId.getOrPut(connection.userUuid) { mutableListOf() }.apply {
            remove(old)
            add(connection)
        }
        return old
    }

    fun remove(connection: Connection) {
        connections.remove(connection.id)
        connectionsByUserId[connection.userUuid]?.let {
            it.remove(connection)
            if (it.isEmpty()) {
                connectionsByUserId.remove(connection.userUuid)
            }
        }
    }

    inline fun forEach(action: (Connection) -> Unit) = connections.values.forEach(action)

    val size get() = connections.size
}

class ConnectionSetAsync {
    @PublishedApi
    internal val lock = Mutex()
    @PublishedApi
    internal val sync = ConnectionSetSync()

    suspend fun byId(id: ConnectionId) = lock.withLock { sync.byId(id) }

    suspend fun byUserId(userId: UUID) = lock.withLock { sync.byUserId(userId) }

    suspend fun add(connection: Connection) = lock.withLock { sync.add(connection) }

    suspend fun remove(connection: Connection) = lock.withLock { sync.remove(connection) }

    suspend inline fun forEach(action: (Connection) -> Unit) = lock.withLock { sync.forEach(action) }

    val size get() = sync.size
}
