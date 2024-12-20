package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.cid
import io.github.gaming32.worldhostserver.serialization.string
import io.github.gaming32.worldhostserver.serialization.uuid
import io.github.gaming32.worldhostserver.util.addWithCircleLimit
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

private val logger = KotlinLogging.logger {}

sealed interface WorldHostC2SMessage {
    sealed interface MessageFactory<M : WorldHostC2SMessage> {
        val id: Int

        val firstProtocol: Int

        fun decode(buf: ByteBuffer): M
    }

    companion object {
        val factoryById: Map<Int, MessageFactory<*>> = buildMap {
            for (messageClass in WorldHostC2SMessage::class.sealedSubclasses) {
                val factory = messageClass.companionObjectInstance ?: throw IllegalStateException(
                    "${messageClass.simpleName} missing factory"
                )
                factory as? MessageFactory<*> ?: throw IllegalStateException(
                    "${messageClass.simpleName} factory not an instance of ${MessageFactory::class.simpleName}"
                )
                if (factory.targetType != messageClass) {
                    throw IllegalStateException("${messageClass.simpleName} factory has wrong target type")
                }
                putIfAbsent(factory.id, factory)?.let {
                    throw IllegalStateException(
                        "Duplicate type ID ${factory.id} between ${it.targetType.simpleName} and ${messageClass.simpleName}"
                    )
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        val <M : WorldHostC2SMessage> MessageFactory<M>.targetType get() =
            this::class.supertypes.first().arguments.single().type?.classifier as KClass<M>

        fun decode(typeId: Int, data: ByteBuffer, maxProtocolVersion: Int? = null): WorldHostC2SMessage {
            val factory = factoryById[typeId]
                ?: throw IllegalArgumentException("Received message with unknown typeId from client: $typeId")
            if (maxProtocolVersion != null && factory.firstProtocol > maxProtocolVersion) {
                throw IllegalArgumentException(
                    "Received too new message from client. " +
                        "Client has version $maxProtocolVersion, " +
                        "but ${factory.targetType.simpleName} was added in ${factory.firstProtocol}"
                )
            }
            return factory.decode(data)
        }
    }

    class HandleContext(
        private val scope: CoroutineScope,
        val server: WorldHostServer,
        val connection: Connection
    ) : CoroutineScope by scope {
        suspend fun Connection.sendSafely(message: WorldHostS2CMessage) {
            try {
                sendMessage(message)
            } catch (e: Exception) {
                if (
                    e !is ClosedSendChannelException ||
                    e.message != "An established connection was aborted by the software in your host machine"
                ) {
                    logger.warn(e) { "Failed to broadcast $message from ${connection.id} to $id" }
                }
            }
        }
    }

    suspend fun HandleContext.handle()

    data class ListOnline(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object : MessageFactory<ListOnline> {
            override val id get() = 0

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = ListOnline(List(buf.int) { buf.uuid })
        }

        override suspend fun HandleContext.handle() {
            val response = WorldHostS2CMessage.IsOnlineTo(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendSafely(response)
                }
            }
        }
    }

    data class FriendRequest(val toUser: UUID) : WorldHostC2SMessage {
        companion object : MessageFactory<FriendRequest> {
            override val id get() = 1

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = FriendRequest(buf.uuid)
        }

        override suspend fun HandleContext.handle() {
            val response = WorldHostS2CMessage.FriendRequest(connection.userUuid, connection.securityLevel)
            val otherConnections = server.whConnections.byUserId(toUser)
            if (otherConnections.isNotEmpty()) {
                for (other in server.whConnections.byUserId(toUser)) {
                    if (other.id == connection.id) continue
                    other.sendSafely(response)
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
        companion object : MessageFactory<PublishedWorld> {
            override val id get() = 2

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = PublishedWorld(List(buf.int) { buf.uuid })
        }

        override suspend fun HandleContext.handle() {
            connection.openToFriends += friends
            val response = WorldHostS2CMessage.PublishedWorld(
                connection.userUuid, connection.id, connection.securityLevel
            )
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendSafely(response)
                }
            }
        }
    }

    data class ClosedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object : MessageFactory<ClosedWorld> {
            override val id get() = 3

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = ClosedWorld(List(buf.int) { buf.uuid })
        }

        override suspend fun HandleContext.handle() {
            connection.openToFriends -= friends.toSet()
            val response = WorldHostS2CMessage.ClosedWorld(connection.userUuid)
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendSafely(response)
                }
            }
        }
    }

    data class RequestJoin(val friend: UUID) : WorldHostC2SMessage {
        companion object : MessageFactory<RequestJoin> {
            override val id get() = 4

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = RequestJoin(buf.uuid)
        }

        override suspend fun HandleContext.handle() {
            if (connection.protocolVersion >= 4) {
                logger.warn { "Connection ${connection.id} tried to use unsupported RequestJoin message" }
                connection.sendSafely(
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
                ?.sendSafely(response)
        }
    }

    data class JoinGranted(val connectionId: ConnectionId, val joinType: JoinType) : WorldHostC2SMessage {
        companion object : MessageFactory<JoinGranted> {
            override val id get() = 5

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = JoinGranted(buf.cid, JoinType.decode(buf))
        }

        override suspend fun HandleContext.handle() {
            val response = joinType.toOnlineGame(connection, server.config)
                ?: return connection.sendSafely(
                    WorldHostS2CMessage.Error("This server does not support JoinType $joinType")
                )
            if (connectionId == connection.id) return
            server.whConnections.byId(connectionId)?.sendSafely(response)
        }
    }

    data class QueryRequest(val friends: Collection<UUID>) : WorldHostC2SMessage {
        companion object : MessageFactory<QueryRequest> {
            override val id get() = 6

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = QueryRequest(List(buf.int) { buf.uuid })
        }

        override suspend fun HandleContext.handle() {
            val response = WorldHostS2CMessage.QueryRequest(
                connection.userUuid, connection.id, connection.securityLevel
            )
            for (friend in friends) {
                for (other in server.whConnections.byUserId(friend)) {
                    if (other.id == connection.id) continue
                    other.sendSafely(response)
                }
            }
        }
    }

    data class QueryResponse(val connectionId: ConnectionId, val data: ByteArray) : WorldHostC2SMessage {
        companion object : MessageFactory<QueryResponse> {
            override val id get() = 7

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = QueryResponse(buf.cid, ByteArray(buf.int).also(buf::get))
        }

        override suspend fun HandleContext.handle() =
            with(NewQueryResponse(connectionId, data)) {
                handle()
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
        companion object : MessageFactory<ProxyS2CPacket> {
            override val id get() = 8

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = ProxyS2CPacket(buf.long, ByteArray(buf.remaining()).also(buf::get))
        }

        override suspend fun HandleContext.handle() {
            server.proxyConnections.withLock {
                this[connectionId]?.let { (cid, channel) ->
                    if (cid == connection.id) {
                        channel.writeFully(data)
                        channel.flush()
                    } else {
                        connection.sendSafely(WorldHostS2CMessage.Error(
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
        companion object : MessageFactory<ProxyDisconnect> {
            override val id get() = 9

            override val firstProtocol get() = 2

            override fun decode(buf: ByteBuffer) = ProxyDisconnect(buf.long)
        }

        override suspend fun HandleContext.handle() {
            server.proxyConnections.withLock {
                this[connectionId]?.let { (cid, channel) ->
                    if (cid == connection.id) {
                        channel.flushAndClose()
                    } else {
                        connection.sendSafely(WorldHostS2CMessage.Error(
                            "Cannot disconnect a connection that's not your own."
                        ))
                    }
                }
            }
        }
    }

    data class RequestDirectJoin(val connectionId: ConnectionId) : WorldHostC2SMessage {
        companion object : MessageFactory<RequestDirectJoin> {
            override val id get() = 10

            override val firstProtocol get() = 4

            override fun decode(buf: ByteBuffer) = RequestDirectJoin(buf.cid)
        }

        override suspend fun HandleContext.handle() {
            val response = WorldHostS2CMessage.RequestJoin(
                connection.userUuid, connection.id, connection.securityLevel
            )
            server.whConnections.byId(connectionId)
                ?.takeIf { it.id != connection.id }
                ?.sendSafely(response)
                ?.let { return }
            connection.sendSafely(WorldHostS2CMessage.ConnectionNotFound(connectionId))
        }
    }

    data class NewQueryResponse(val connectionId: ConnectionId, val data: ByteArray) : WorldHostC2SMessage {
        companion object : MessageFactory<NewQueryResponse> {
            override val id get() = 11

            override val firstProtocol get() = 5

            override fun decode(buf: ByteBuffer) = NewQueryResponse(buf.cid, ByteArray(buf.remaining()).also(buf::get))
        }

        override suspend fun HandleContext.handle() {
            if (connectionId == connection.id) return
            val otherConnection = server.whConnections.byId(connectionId) ?: return
            otherConnection.sendSafely(
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
        val punchId: UUID,
        val myHost: String,
        val myPort: Int,
        val myLocalHost: String,
        val myLocalPort: Int
    ) : WorldHostC2SMessage {
        companion object : MessageFactory<RequestPunchOpen> {
            override val id get() = 12

            override val firstProtocol get() = 7

            override fun decode(buf: ByteBuffer) = RequestPunchOpen(
                buf.cid, buf.string, buf.uuid,
                buf.string, buf.short.toUShort().toInt(),
                buf.string, buf.short.toUShort().toInt()
            )
        }

        override suspend fun HandleContext.handle() {
            val targetClient = server.whConnections.byId(targetConnection)
                ?: return connection.sendSafely(WorldHostS2CMessage.PunchRequestCancelled(punchId))
            if (targetClient.protocolVersion < 7) {
                return connection.sendSafely(WorldHostS2CMessage.PunchRequestCancelled(punchId))
            }
            targetClient.sendSafely(WorldHostS2CMessage.PunchOpenRequest(
                punchId, purpose, myHost, myPort, connection.id, connection.userUuid, connection.securityLevel
            ))
        }
    }

    data class PunchFailed(val targetConnection: ConnectionId, val punchId: UUID) : WorldHostC2SMessage {
        companion object : MessageFactory<PunchFailed> {
            override val id get() = 13

            override val firstProtocol get() = 7

            override fun decode(buf: ByteBuffer) = PunchFailed(buf.cid, buf.uuid)
        }

        override suspend fun HandleContext.handle() {
            server.whConnections.byId(targetConnection)
                ?.sendSafely(WorldHostS2CMessage.PunchRequestCancelled(punchId))
        }
    }

    data class BeginPortLookup(val lookupId: UUID) : WorldHostC2SMessage {
        companion object : MessageFactory<BeginPortLookup> {
            override val id get() = 14

            override val firstProtocol get() = 7

            override fun decode(buf: ByteBuffer) = BeginPortLookup(buf.uuid)
        }

        override suspend fun HandleContext.handle() {
            val request = ActivePortLookup(lookupId, connection.id)
            val oldRequest = server.portLookups.withLock { putIfAbsent(lookupId, request) }
            if (oldRequest != null) {
                connection.sendSafely(WorldHostS2CMessage.CancelPortLookup(lookupId))
                connection.sendSafely(WorldHostS2CMessage.Error("Duplicate lookup ID $lookupId"))
                return
            }
            server.portLookupsByExpiryAtSecond.withLock {
                getOrPut(System.currentTimeMillis() / 1000L + PORT_LOOKUP_EXPIRY) { mutableListOf() }.add(lookupId)
            }
        }
    }

    data class PunchSuccess(
        val connectionId: ConnectionId, val punchId: UUID, val host: String, val port: Int
    ) : WorldHostC2SMessage {
        companion object : MessageFactory<PunchSuccess> {
            override val id get() = 15

            override val firstProtocol get() = 7

            override fun decode(buf: ByteBuffer) =
                PunchSuccess(buf.cid, buf.uuid, buf.string, buf.short.toUShort().toInt())
        }

        override suspend fun HandleContext.handle() {
            server.whConnections.byId(connectionId)
                ?.sendSafely(WorldHostS2CMessage.PunchSuccess(punchId, host, port))
        }
    }
}
