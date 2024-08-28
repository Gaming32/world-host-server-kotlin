package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.FieldedSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetAddress
import java.util.*

private val logger = KotlinLogging.logger {}

sealed interface WorldHostS2CMessage : FieldedSerializer {
    val typeId: Byte
    val firstProtocol: Int

    data class Error(val message: String, val critical: Boolean = false) : WorldHostS2CMessage {
        override val typeId: Byte get() = 0
        override val firstProtocol get() = 2
        override val fields = listOf(message, critical)
    }

    data class IsOnlineTo(val user: UUID) : WorldHostS2CMessage {
        override val typeId: Byte get() = 1
        override val firstProtocol get() = 2
        override val fields = listOf(user)
    }

    data class OnlineGame(
        val host: String,
        val port: Int,
        val ownerCid: ConnectionId,
        val isPunchProtocol: Boolean = false
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 2
        override val firstProtocol get() = 2
        override val fields = listOf(host, port.toShort(), ownerCid, isPunchProtocol)

        init {
            if (isPunchProtocol && (host.isNotEmpty() || port != 0)) {
                logger.warn {
                    "WorldHostS2CMessage.OnlineGame constructed with isPunchProtocol, but host and port are non-empty"
                }
            }
        }
    }

    data class FriendRequest(val fromUser: UUID, val security: SecurityLevel) : WorldHostS2CMessage {
        override val typeId: Byte get() = 3
        override val firstProtocol get() = 2
        override val fields = listOf(fromUser, security)
    }

    data class PublishedWorld(
        val user: UUID,
        val connectionId: ConnectionId,
        val security: SecurityLevel
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 4
        override val firstProtocol get() = 2
        override val fields = listOf(user, connectionId, security)
    }

    data class ClosedWorld(val user: UUID) : WorldHostS2CMessage {
        override val typeId: Byte get() = 5
        override val firstProtocol get() = 2
        override val fields = listOf(user)
    }

    data class RequestJoin(
        val user: UUID,
        val connectionId: ConnectionId,
        val security: SecurityLevel
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 6
        override val firstProtocol get() = 2
        override val fields = listOf(user, connectionId, security)
    }

    data class QueryRequest(
        val friend: UUID,
        val connectionId: ConnectionId,
        val security: SecurityLevel
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 7
        override val firstProtocol get() = 2
        override val fields = listOf(friend, connectionId, security)
    }

    @Deprecated(
        "QueryResponse uses an old format. NewQueryResponse should be used instead.",
        ReplaceWith("NewQueryResponse")
    )
    data class QueryResponse(val friend: UUID, val data: ByteArray) : WorldHostS2CMessage {
        override val typeId: Byte get() = 8
        override val firstProtocol get() = 2
        override val fields = listOf(friend, data.size, data)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            @Suppress("DEPRECATION")
            other as QueryResponse

            if (friend != other.friend) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = friend.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ProxyC2SPacket(val connectionId: Long, val data: ByteArray) : WorldHostS2CMessage {
        override val typeId: Byte get() = 9
        override val firstProtocol get() = 2
        override val fields = listOf(connectionId, data)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ProxyC2SPacket

            if (connectionId != other.connectionId) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = connectionId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ProxyConnect(val connectionId: Long, val remoteAddr: InetAddress) : WorldHostS2CMessage {
        override val typeId: Byte get() = 10
        override val firstProtocol get() = 2
        override val fields = listOf(connectionId, remoteAddr)
    }

    data class ProxyDisconnect(val connectionId: Long) : WorldHostS2CMessage {
        override val typeId: Byte get() = 11
        override val firstProtocol get() = 2
        override val fields = listOf(connectionId)
    }

    data class ConnectionInfo(
        val connectionId: ConnectionId,
        val baseIp: String,
        val basePort: Int,
        val userIp: String,
        val protocolVersion: Int,
        val punchPort: Int
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 12
        override val firstProtocol get() = 2
        override val fields = listOf(
            connectionId, baseIp, basePort.toShort(), userIp, protocolVersion, punchPort.toShort()
        )
    }

    data class ExternalProxyServer(
        val host: String,
        val port: Int,
        val baseAddr: String,
        val mcPort: Int
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 13
        override val firstProtocol get() = 2
        override val fields = listOf(host, port.toShort(), baseAddr, mcPort.toShort())
    }

    data class OutdatedWorldHost(val recommendedVersion: String) : WorldHostS2CMessage {
        override val typeId: Byte get() = 14
        override val firstProtocol get() = 4
        override val fields = listOf(recommendedVersion)
    }

    data class ConnectionNotFound(val connectionId: ConnectionId) : WorldHostS2CMessage {
        override val typeId: Byte get() = 15
        override val firstProtocol get() = 4
        override val fields = listOf(connectionId)
    }

    data class NewQueryResponse(val friend: UUID, val data: ByteArray) : WorldHostS2CMessage {
        override val typeId: Byte get() = 16
        override val firstProtocol get() = 5
        override val fields = listOf(friend, data)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NewQueryResponse

            if (friend != other.friend) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = friend.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class Warning(val message: String, val important: Boolean) : WorldHostS2CMessage {
        override val typeId: Byte get() = 17
        override val firstProtocol get() = 6
        override val fields = listOf(message, important)
    }

    data class PunchOpenRequest(
        val punchId: UUID,
        val purpose: String,
        val fromHost: String,
        val fromPort: Int,
        val connectionId: ConnectionId,
        val user: UUID,
        val security: SecurityLevel
    ) : WorldHostS2CMessage {
        override val typeId: Byte get() = 18
        override val firstProtocol get() = 7
        override val fields = listOf(punchId, purpose, fromHost, fromPort.toShort(), connectionId, user, security)
    }

    data class CancelPortLookup(val lookupId: UUID) : WorldHostS2CMessage {
        override val typeId: Byte get() = 19
        override val firstProtocol get() = 7
        override val fields = listOf(lookupId)
    }

    data class PortLookupSuccess(val lookupId: UUID, val host: String, val port: Int) : WorldHostS2CMessage {
        override val typeId: Byte get() = 20
        override val firstProtocol get() = 7
        override val fields = listOf(lookupId, host, port.toShort())
    }

    data class PunchRequestCancelled(val punchId: UUID) : WorldHostS2CMessage {
        override val typeId: Byte get() = 21
        override val firstProtocol get() = 7
        override val fields = listOf(punchId)
    }

    data class PunchSuccess(val punchId: UUID, val host: String, val port: Int) : WorldHostS2CMessage {
        override val typeId: Byte get() = 22
        override val firstProtocol get() = 7
        override val fields = listOf(punchId, host, port.toShort())
    }
}
