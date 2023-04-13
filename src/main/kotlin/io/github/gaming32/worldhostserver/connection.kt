package io.github.gaming32.worldhostserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class Connection(
    val id: UUID,
    val address: String,
    val userUuid: UUID,
    val session: WorldHostServerEndpoint,
    var country: String? = null,
    var open: Boolean = true
) {
    constructor(ids: IdsPair, address: String, session: WorldHostServerEndpoint) :
        this(ids.connectionId, address, ids.userId, session)

    override fun toString(): String {
        return "Connection(id=$id, address=$address, userUuid=$userUuid)"
    }

    fun send(message: WorldHostS2CMessage) {
        session.session.asyncRemote.sendObject(message)
    }
}

class ConnectionSetSync {
    @PublishedApi
    internal val connections = mutableMapOf<UUID, Connection>()
    private val connectionsByUserId = mutableMapOf<UUID, MutableList<Connection>>()

    fun byId(id: UUID) = connections[id]

    fun byUserId(userId: UUID) = connectionsByUserId[userId] ?: listOf()

    fun add(connection: Connection) {
        if (connection.id in connections) {
            throw IllegalStateException("Connection ${connection.id} already connected!")
        }
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

    inline fun forEach(action: (Connection) -> Unit) = connections.values.forEach(action)

    val size get() = connections.size
}

class ConnectionSetAsync {
    @PublishedApi
    internal val lock = Mutex()
    @PublishedApi
    internal val sync = ConnectionSetSync()

    suspend fun byId(id: UUID) = lock.withLock { sync.byId(id) }

    suspend fun byUserId(userId: UUID) = lock.withLock { sync.byUserId(userId) }

    suspend fun add(connection: Connection) = lock.withLock { sync.add(connection) }

    suspend fun remove(connection: Connection) = lock.withLock { sync.remove(connection) }

    suspend inline fun forEach(action: (Connection) -> Unit) = lock.withLock { sync.forEach(action) }

    val size get() = sync.size
}

class ConnectionSetConcurrent {
    @PublishedApi
    internal val lock = ReentrantLock()
    @PublishedApi
    internal val sync = ConnectionSetSync()

    fun byId(id: UUID) = lock.withLock { sync.byId(id) }

    fun byUserId(userId: UUID) = lock.withLock { sync.byUserId(userId) }

    fun add(connection: Connection) = lock.withLock { sync.add(connection) }

    fun remove(connection: Connection) = lock.withLock { sync.remove(connection) }

    inline fun forEach(action: (Connection) -> Unit) = lock.withLock { sync.forEach(action) }

    val size get() = sync.size
}
