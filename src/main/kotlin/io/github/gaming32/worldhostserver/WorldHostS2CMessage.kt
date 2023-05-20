package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.FieldedSerializer
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*

sealed interface WorldHostS2CMessage : FieldedSerializer {
    val packetId: Byte

    data class Error(val message: String) : WorldHostS2CMessage {
        override val packetId: Byte get() = 0
        override val fields = listOf(message)
    }

    data class IsOnlineTo(val user: UUID) : WorldHostS2CMessage {
        override val packetId: Byte get() = 1
        override val fields = listOf(user)
    }

    data class OnlineGame(val host: String, val port: Int, val owner: UUID) : WorldHostS2CMessage {
        override val packetId: Byte get() = 2
        override val fields = listOf(host, port.toShort(), owner)
    }

    data class FriendRequest(val fromUser: UUID) : WorldHostS2CMessage {
        override val packetId: Byte get() = 3
        override val fields = listOf(fromUser)
    }

    data class PublishedWorld(val user: UUID, val connectionId: ConnectionId) : WorldHostS2CMessage {
        override val packetId: Byte get() = 4
        override val fields = listOf(user, connectionId)
    }

    data class ClosedWorld(val user: UUID) : WorldHostS2CMessage {
        override val packetId: Byte get() = 5
        override val fields = listOf(user)
    }

    data class RequestJoin(val user: UUID, val connectionId: ConnectionId) : WorldHostS2CMessage {
        override val packetId: Byte get() = 6
        override val fields = listOf(user, connectionId)
    }

    data class QueryRequest(val friend: UUID, val connectionId: ConnectionId) : WorldHostS2CMessage {
        override val packetId: Byte get() = 7
        override val fields = listOf(friend, connectionId)
    }

    data class QueryResponse(val friend: UUID, val data: ByteArray) : WorldHostS2CMessage {
        override val packetId: Byte get() = 8
        override val fields = listOf(friend, data.size, data)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

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
        override val packetId: Byte get() = 9
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
        override val packetId: Byte get() = 10
        override val fields = listOf(connectionId, remoteAddr)
    }

    data class ProxyDisconnect(val connectionId: Long) : WorldHostS2CMessage {
        override val packetId: Byte get() = 11
        override val fields = listOf(connectionId)
    }

    data class ConnectionInfo(
        val connectionId: ConnectionId,
        val baseIp: String,
        val basePort: Int,
        val userIp: String,
        val protocolVersion: Int
    ) : WorldHostS2CMessage {
        override val packetId: Byte get() = 12
        override val fields = listOf(connectionId, baseIp, basePort.toShort(), userIp, protocolVersion)
    }

    data class ExternalProxyServer(
        val host: String,
        val port: Int,
        val baseAddr: String,
        val mcPort: Int
    ) : WorldHostS2CMessage {
        override val packetId: Byte get() = 13
        override val fields = listOf(host, port.toShort(), baseAddr, mcPort.toShort())
    }

    data class OutdatedWorldHost(val recommendedVersion: String) : WorldHostS2CMessage {
        override val packetId: Byte get() = 14
        override val fields = listOf(recommendedVersion)
    }

    override fun encode(buf: ByteBuffer) = super.encode(buf.put(packetId))

    override fun encodedLength() = 1 + super.encodedLength()
}
