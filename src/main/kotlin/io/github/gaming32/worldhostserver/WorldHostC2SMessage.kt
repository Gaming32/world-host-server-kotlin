package io.github.gaming32.worldhostserver

import io.ktor.server.websocket.*
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
            else -> throw IllegalArgumentException("Received packet with unknown type_id from client: $typeId")
        }
    }

    suspend fun DefaultWebSocketServerSession.handle(
        config: ServerConfig,
        connections: ConnectionSetAsync,
        connection: Connection
    )

    data class ListOnline(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.IsOnlineTo(connection.userUuid)
            for (friend in friends) {
                for (other in connections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class FriendRequest(val toUser: UUID) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.FriendRequest(connection.userUuid)
            for (other in connections.byUserId(toUser)) {
                if (other.id == connection.id) continue
                other.session.sendSerialized(response)
            }
        }
    }

    data class PublishedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.PublishedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in connections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class ClosedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.ClosedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in connections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class RequestJoin(val friend: UUID) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.RequestJoin(connection.userUuid, connection.id)
            connections.byUserId(friend)
                .lastOrNull()
                ?.takeIf { it.id != connection.id }
                ?.session
                ?.sendSerialized(response)
        }
    }

    data class JoinGranted(val connectionId: UUID, val joinType: JoinType) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = joinType.toOnlineGame(connection, config)
                ?: return connection.session.sendSerialized(
                    WorldHostS2CMessage.Error("This server does not support JoinType $joinType")
                )
            if (connectionId == connection.id) return
            connections.byId(connectionId)?.session?.sendSerialized(response)
        }
    }

    data class QueryRequest(val friends: Collection<UUID>) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.QueryRequest(connection.userUuid, connection.id)
            for (friend in friends) {
                for (other in connections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.session.sendSerialized(response)
                }
            }
        }
    }

    data class QueryResponse(val connectionId: UUID, val data: ByteArray) : WorldHostC2SMessage {
        override suspend fun DefaultWebSocketServerSession.handle(
            config: ServerConfig,
            connections: ConnectionSetAsync,
            connection: Connection
        ) {
            val response = WorldHostS2CMessage.QueryResponse(connection.userUuid, data)
            if (connectionId == connection.id) return
            connections.byId(connectionId)?.session?.sendSerialized(response)
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
}
