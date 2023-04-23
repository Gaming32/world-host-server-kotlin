package io.github.gaming32.worldhostserver

import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.util.*

sealed interface WorldHostC2SMessage {
    companion object {
        fun decode(buf: ByteBuffer) = when (val typeId = buf.byte.toUByte().toInt()) {
            0 -> ListOnline(List(buf.int) { buf.uuid })
            1 -> FriendRequest(buf.uuid)
            2 -> PublishedWorld(List(buf.int) { buf.uuid })
            3 -> ClosedWorld(List(buf.int) { buf.uuid })
            4 -> RequestJoin(buf.uuid)
            5 -> JoinGranted(buf.cid, JoinType.decode(buf))
            6 -> QueryRequest(List(buf.int) { buf.uuid })
            7 -> QueryResponse(buf.cid, ByteArray(buf.int).also(buf::get))
            8 -> ProxyS2CPacket(buf.long, ByteArray(buf.remaining()).also(buf::get))
            9 -> ProxyDisconnect(buf.long)
            else -> throw IllegalArgumentException("Received packet with unknown type_id from client: $typeId")
        }
    }

    suspend fun CoroutineScope.handle(
        server: WorldHostServer,
        connection: Connection
    )

    data class ListOnline(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.IsOnlineTo(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.socket.sendMessage(response)
                }
            }
        }
    }

    data class FriendRequest(val toUser: UUID) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.FriendRequest(connection.userUuid)
            for (other in server.whConnections.byUserId(toUser)) {
                if (other.id == connection.id) continue
                other.socket.sendMessage(response)
            }
        }
    }

    data class PublishedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.PublishedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.socket.sendMessage(response)
                }
            }
        }
    }

    data class ClosedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.ClosedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.socket.sendMessage(response)
                }
            }
        }
    }

    data class RequestJoin(val friend: UUID) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.RequestJoin(connection.userUuid, connection.id)
            server.whConnections.byUserId(friend)
                .lastOrNull()
                ?.takeIf { it.id != connection.id }
                ?.socket
                ?.sendMessage(response)
        }
    }

    data class JoinGranted(val connectionId: ConnectionId, val joinType: JoinType) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = joinType.toOnlineGame(connection, server.config)
                ?: return connection.socket.sendMessage(
                    WorldHostS2CMessage.Error("This server does not support JoinType $joinType")
                )
            if (connectionId == connection.id) return
            server.whConnections.byId(connectionId)?.socket?.sendMessage(response)
        }
    }

    data class QueryRequest(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.QueryRequest(connection.userUuid, connection.id)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.socket.sendMessage(response)
                }
            }
        }
    }

    data class QueryResponse(val connectionId: ConnectionId, val data: ByteArray) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.QueryResponse(connection.userUuid, data)
            if (connectionId == connection.id) return
            server.whConnections.byId(connectionId)?.socket?.sendMessage(response)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as QueryResponse

            if (connectionId != other.connectionId) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = connectionId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ProxyS2CPacket(val connectionId: Long, val data: ByteArray) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            server.proxyConnections[connectionId]?.apply {
                writeFully(data)
                flush()
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ProxyS2CPacket

            if (connectionId != other.connectionId) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = connectionId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ProxyDisconnect(val connectionId: Long) : WorldHostC2SMessage {
        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            server.proxyConnections[connectionId]?.close()
        }
    }
}
