package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.cid
import io.github.gaming32.worldhostserver.serialization.punchCookie
import io.github.gaming32.worldhostserver.serialization.string
import io.github.gaming32.worldhostserver.serialization.uuid
import io.github.gaming32.worldhostserver.util.addWithCircleLimit
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.util.*

private val logger = KotlinLogging.logger {}

sealed interface WorldHostC2SMessage {
    companion object {
        fun isEncrypted(typeId: Int) = when (typeId) {
            RequestPunchOpen.ID,
            PunchRequestInvalid.ID -> true
            else -> false
        }

        fun decode(typeId: Int, buf: ByteBuffer) = when (typeId) {
            ListOnline.ID -> ListOnline.decode(buf)
            FriendRequest.ID -> FriendRequest.decode(buf)
            PublishedWorld.ID -> PublishedWorld.decode(buf)
            ClosedWorld.ID -> ClosedWorld.decode(buf)
            RequestJoin.ID -> RequestJoin.decode(buf)
            JoinGranted.ID -> JoinGranted.decode(buf)
            QueryRequest.ID -> QueryRequest.decode(buf)
            QueryResponse.ID -> QueryResponse.decode(buf)
            ProxyS2CPacket.ID -> ProxyS2CPacket.decode(buf)
            ProxyDisconnect.ID -> ProxyDisconnect.decode(buf)
            RequestDirectJoin.ID -> RequestDirectJoin.decode(buf)
            NewQueryResponse.ID -> NewQueryResponse.decode(buf)
            RequestPunchOpen.ID -> RequestPunchOpen.decode(buf)
            PunchRequestInvalid.ID -> PunchRequestInvalid.decode(buf)
            else -> throw IllegalArgumentException("Received packet with unknown typeId from client: $typeId")
        }
    }

    suspend fun CoroutineScope.handle(
        server: WorldHostServer,
        connection: Connection
    )

    data class ListOnline(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object {
            const val ID = 0

            fun decode(buf: ByteBuffer) = ListOnline(List(buf.int) { buf.uuid })
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val response = WorldHostS2CMessage.IsOnlineTo(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendMessage(response)
                }
            }
        }
    }

    data class FriendRequest(val toUser: UUID) : WorldHostC2SMessage {
        companion object {
            const val ID = 1

            fun decode(buf: ByteBuffer) = FriendRequest(buf.uuid)
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val response = WorldHostS2CMessage.FriendRequest(connection.userUuid, connection.securityLevel)
            val otherConnections = server.whConnections.byUserId(toUser)
            if (otherConnections.isNotEmpty()) {
                for (other in server.whConnections.byUserId(toUser)) {
                    if (other.id == connection.id) continue
                    other.sendMessage(response)
                }
            } else if (connection.securityLevel > SecurityLevel.INSECURE) {
                val removedRemembered = server.rememberedFriendRequests.withLock {
                    val myRequests = getOrPut(connection.userUuid) { mutableSetOf() }
                    myRequests.addWithCircleLimit(toUser, 5)
                }
                val removedReceived = server.receivedFriendRequests.withLock {
                    this[removedRemembered]?.let {
                        it -= connection.userUuid
                        if (it.isEmpty()) {
                            remove(removedRemembered)
                        }
                    }
                    getOrPut(toUser) { mutableSetOf() }.addWithCircleLimit(connection.userUuid, 10)
                }
                if (removedReceived != null) {
                    server.rememberedFriendRequests.withLock {
                        this[removedReceived]?.let { it -= toUser }
                    }
                }
            }
        }
    }

    data class PublishedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object {
            const val ID = 2

            fun decode(buf: ByteBuffer) = ListOnline(List(buf.int) { buf.uuid })
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            connection.openToFriends += friends
            val response = WorldHostS2CMessage.PublishedWorld(
                connection.userUuid, connection.id, connection.securityLevel
            )
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendMessage(response)
                }
            }
        }
    }

    data class ClosedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object {
            const val ID = 3

            fun decode(buf: ByteBuffer) = ClosedWorld(List(buf.int) { buf.uuid })
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            connection.openToFriends -= friends.toSet()
            val response = WorldHostS2CMessage.ClosedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendMessage(response)
                }
            }
        }
    }

    data class RequestJoin(val friend: UUID) : WorldHostC2SMessage {
        companion object {
            const val ID = 4

            fun decode(buf: ByteBuffer) = RequestJoin(buf.uuid)
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            if (connection.protocolVersion >= 4) {
                logger.warn { "Connection ${connection.id} tried to use unsupported RequestJoin message" }
                connection.sendMessage(
                    WorldHostS2CMessage.Error(
                        "Please use the v4+ RequestDirectJoin message instead of the unsupported RequestJoin message"
                    )
                )
                return
            }
            val response = WorldHostS2CMessage.RequestJoin(
                connection.userUuid, connection.id, connection.securityLevel
            )
            server.whConnections.byUserId(friend)
                .lastOrNull()
                ?.takeIf { it.id != connection.id }
                ?.sendMessage(response)
        }
    }

    data class JoinGranted(val connectionId: ConnectionId, val joinType: JoinType) : WorldHostC2SMessage {
        companion object {
            const val ID = 5

            fun decode(buf: ByteBuffer) = JoinGranted(buf.cid, JoinType.decode(buf))
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val response = joinType.toOnlineGame(connection, server.config)
                ?: return connection.sendMessage(
                    WorldHostS2CMessage.Error("This server does not support JoinType $joinType")
                )
            if (connectionId == connection.id) return
            server.whConnections.byId(connectionId)?.sendMessage(response)
        }
    }

    data class QueryRequest(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object {
            const val ID = 6

            fun decode(buf: ByteBuffer) = QueryRequest(List(buf.int) { buf.uuid })
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val response = WorldHostS2CMessage.QueryRequest(
                connection.userUuid, connection.id, connection.securityLevel
            )
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendMessage(response)
                }
            }
        }
    }

    data class QueryResponse(val connectionId: ConnectionId, val data: ByteArray) : WorldHostC2SMessage {
        companion object {
            const val ID = 7

            fun decode(buf: ByteBuffer) = QueryResponse(buf.cid, ByteArray(buf.int).also(buf::get))
        }

        @Suppress("SuspendFunctionOnCoroutineScope")
        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) =
            with(NewQueryResponse(connectionId, data)) {
                handle(server, connection)
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
        companion object {
            const val ID = 8

            fun decode(buf: ByteBuffer) = ProxyS2CPacket(buf.long, ByteArray(buf.remaining()).also(buf::get))
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            server.proxyConnections.withLock {
                this[connectionId]?.let { (cid, channel) ->
                    if (cid == connection.id) {
                        channel.writeFully(data)
                        channel.flush()
                    } else {
                        connection.sendMessage(WorldHostS2CMessage.Error(
                            "Cannot send a packet to a connection that's not your own."
                        ))
                    }
                }
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
        companion object {
            const val ID = 9

            fun decode(buf: ByteBuffer) = ProxyDisconnect(buf.long)
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            server.proxyConnections.withLock {
                this[connectionId]?.let { (cid, channel) ->
                    if (cid == connection.id) {
                        channel.close()
                    } else {
                        connection.sendMessage(WorldHostS2CMessage.Error(
                            "Cannot disconnect a connection that's not your own."
                        ))
                    }
                }
            }
        }
    }

    data class RequestDirectJoin(val connectionId: ConnectionId) : WorldHostC2SMessage {
        companion object {
            const val ID = 10

            fun decode(buf: ByteBuffer) = RequestDirectJoin(buf.cid)
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val response = WorldHostS2CMessage.RequestJoin(
                connection.userUuid, connection.id, connection.securityLevel
            )
            server.whConnections.byId(connectionId)
                ?.takeIf { it.id != connection.id }
                ?.sendMessage(response)
                ?.let { return }
            connection.sendMessage(WorldHostS2CMessage.ConnectionNotFound(connectionId))
        }
    }

    data class NewQueryResponse(val connectionId: ConnectionId, val data: ByteArray) : WorldHostC2SMessage {
        companion object {
            const val ID = 11

            fun decode(buf: ByteBuffer) = NewQueryResponse(buf.cid, ByteArray(buf.remaining()).also(buf::get))
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            if (connectionId == connection.id) return
            val otherConnection = server.whConnections.byId(connectionId) ?: return
            otherConnection.sendMessage(
                if (otherConnection.protocolVersion < 5) {
                    @Suppress("DEPRECATION")
                    WorldHostS2CMessage.QueryResponse(connection.userUuid, data)
                } else {
                    WorldHostS2CMessage.NewQueryResponse(connection.userUuid, data)
                }
            )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NewQueryResponse

            if (connectionId != other.connectionId) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = connectionId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class RequestPunchOpen(
        val targetConnection: ConnectionId,
        val purpose: String,
        val cookie: PunchCookie
    ) : WorldHostC2SMessage {
        companion object {
            const val ID = 12

            fun decode(buf: ByteBuffer) = RequestPunchOpen(buf.cid, buf.string, buf.punchCookie)
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val targetClient = server.whConnections.byId(targetConnection)
                ?: return connection.sendMessage(WorldHostS2CMessage.PunchRequestCancelled(cookie))
            val request = ActivePunchRequest(cookie, connection.id, targetConnection)
            val oldRequest = server.punchRequests.withLock { putIfAbsent(cookie, request) }
            if (oldRequest != null) {
                connection.sendMessage(WorldHostS2CMessage.PunchRequestCancelled(cookie))
                connection.sendMessage(WorldHostS2CMessage.Error("Duplicate punch cookie $cookie"))
                return
            }
            if (targetClient.protocolVersion < 7) {
                return connection.sendMessage(WorldHostS2CMessage.PunchRequestCancelled(cookie))
            }
            server.punchRequestsByExpiryAtSecond.withLock {
                getOrPut(System.currentTimeMillis() / 1000L + PUNCH_REQUEST_EXPIRY) { mutableListOf() }.add(cookie)
            }
            targetClient.sendMessage(WorldHostS2CMessage.PunchOpenRequest(
                cookie, purpose, connection.userUuid, connection.securityLevel
            ))
        }
    }

    data class PunchRequestInvalid(val cookie: PunchCookie) : WorldHostC2SMessage {
        companion object {
            const val ID = 13

            fun decode(buf: ByteBuffer) = PunchRequestInvalid(buf.punchCookie)
        }

        override suspend fun CoroutineScope.handle(server: WorldHostServer, connection: Connection) {
            val request = server.punchRequests.withLock { remove(cookie) } ?: return
            server.whConnections.byId(request.sourceClient)
                ?.sendMessage(WorldHostS2CMessage.PunchRequestCancelled(cookie))
        }
    }
}
