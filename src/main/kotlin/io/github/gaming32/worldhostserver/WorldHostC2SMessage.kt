package io.github.gaming32.worldhostserver

import io.ktor.server.websocket.*
import io.ktor.utils.io.*
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
            5 -> JoinGranted(buf.uuid, JoinType.decode(buf))
            6 -> QueryRequest(List(buf.int) { buf.uuid })
            7 -> QueryResponse(buf.uuid, ByteArray(buf.int).also(buf::get))
            8 -> ProxyS2CPacket(buf.long, ByteArray(buf.remaining()).also(buf::get))
            9 -> ProxyDisconnect(buf.long)
            else -> throw IllegalArgumentException("Received packet with unknown type_id from client: $typeId")
        }
    }

    suspend fun DefaultWebSocketServerSession.handle(
        server: WorldHostServer,
        connection: Connection
    )

    data class ListOnline(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.IsOnlineTo(connection.userUuid)
            for (friend in friends) {
                for (other in server.wsConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class FriendRequest(val toUser: UUID) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.FriendRequest(connection.userUuid)
            for (other in server.wsConnections.byUserId(toUser)) {
                if (other.id == connection.id) continue
                other.session.sendSerialized(response)
            }
        }
    }

    data class PublishedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.PublishedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in server.wsConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class ClosedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.ClosedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in server.wsConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class RequestJoin(val friend: UUID) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.RequestJoin(connection.userUuid, connection.id)
            server.wsConnections.byUserId(friend)
                .lastOrNull()
                ?.takeIf { it.id != connection.id }
                ?.session
                ?.sendSerialized(response)
        }
    }

    data class JoinGranted(val connectionId: UUID, val joinType: JoinType) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = joinType.toOnlineGame(connection, server.config)
                ?: return connection.session.sendSerialized(
                    WorldHostS2CMessage.Error("This server does not support JoinType $joinType")
                )
            if (connectionId == connection.id) return
            server.wsConnections.byId(connectionId)?.session?.sendSerialized(response)
        }
    }

    data class QueryRequest(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.QueryRequest(connection.userUuid, connection.id)
            for (friend in friends) {
                for (other in server.wsConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class QueryResponse(val connectionId: UUID, val data: ByteArray) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            server: WorldHostServer,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.QueryResponse(connection.userUuid, data)
            if (connectionId == connection.id) return
            server.wsConnections.byId(connectionId)?.session?.sendSerialized(response)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as QueryResponse

            if (connectionId != other.connectionId) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = connectionId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ProxyS2CPacket(val connectionId: Long, val data: ByteArray) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
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
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = connectionId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ProxyDisconnect(val connectionId: Long) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(server: WorldHostServer, connection: Connection) {
            server.proxyConnections[connectionId]?.close()
        }
    }
}
