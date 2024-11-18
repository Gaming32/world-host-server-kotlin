package io.github.gaming32.worldhostserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import javax.crypto.Cipher

data class Connection(
    val id: ConnectionId,
    val address: String,
    val userUuid: UUID,
    val socket: SocketWrapper,
    val protocolVersion: Int,
    val decryptCipher: Cipher?,
    val encryptCipher: Cipher?,
    var country: String? = null,
    var externalProxy: ExternalProxy? = null,
    var open: Boolean = true,
    val openToFriends: MutableSet<UUID> = mutableSetOf()
) {
    val securityLevel get() = SecurityLevel.from(
        userUuid, secureAuth = protocolVersion >= ProtocolVersions.NEW_AUTH_PROTOCOL
    )

    override fun toString(): String {
        return "Connection(id=$id, address=$address, userUuid=$userUuid)"
    }

    suspend fun recvMessage() = socket.recvMessage(decryptCipher, encryptCipher, protocolVersion)

    suspend fun sendMessage(message: WorldHostS2CMessage) {
        if (protocolVersion >= message.firstProtocol) {
            socket.sendMessage(message, encryptCipher)
        }
    }

    suspend fun closeError(message: String) {
        socket.closeError(message, encryptCipher)
    }
}

class ConnectionSetSync {
    @PublishedApi
    internal val connections = mutableMapOf<ConnectionId, Connection>()
    private val connectionsByUserId = mutableMapOf<UUID, MutableList<Connection>>()

    fun byId(id: ConnectionId) = connections[id]

    fun byUserId(userId: UUID) = connectionsByUserId[userId] ?: listOf()

    fun add(connection: Connection, force: Boolean = false): Boolean {
        if (!force && connection.id in connections) {
            return false
        }
        val old = connections.put(connection.id, connection)
        connectionsByUserId.getOrPut(connection.userUuid) { mutableListOf() }.apply {
            remove(old)
            add(connection)
        }
        return true
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

    suspend fun add(connection: Connection, force: Boolean = false) = lock.withLock { sync.add(connection, force) }

    suspend fun remove(connection: Connection) = lock.withLock { sync.remove(connection) }

    suspend inline fun forEach(action: (Connection) -> Unit) = lock.withLock { sync.forEach(action) }

    val size get() = sync.size
}
