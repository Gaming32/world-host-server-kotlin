package io.github.gaming32.worldhostserver

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*

sealed interface WorldHostS2CMessage {
    fun encode(buf: ByteBuffer): ByteBuffer

    fun encodedSize(): Int

    data class Error(val message: String) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(0).putString(message)

        override fun encodedSize() = 1 + 2 + message.length
    }

    data class IsOnlineTo(val user: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(1).putUuid(user)

        override fun encodedSize() = 1 + 16
    }

    data class OnlineGame(val host: String, val port: Int, val owner: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(2).putString(host).putShort(port.toShort()).putUuid(owner)

        override fun encodedSize() = 1 + 2 + host.length + 2 + 16
    }

    data class FriendRequest(val fromUser: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(3).putUuid(fromUser)

        override fun encodedSize() = 1 + 16
    }

    data class PublishedWorld(val user: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(4).putUuid(user)

        override fun encodedSize() = 1 + 16
    }

    data class ClosedWorld(val user: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(5).putUuid(user)

        override fun encodedSize() = 1 + 16
    }

    data class RequestJoin(val user: UUID, val connectionId: ConnectionId) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(6).putUuid(user).putCid(connectionId)

        override fun encodedSize() = 1 + 16 + 8
    }

    data class QueryRequest(val friend: UUID, val connectionId: ConnectionId) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(7).putUuid(friend).putCid(connectionId)

        override fun encodedSize() = 1 + 16 + 8
    }

    data class QueryResponse(val friend: UUID, val data: ByteArray) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(8).putUuid(friend).putInt(data.size).put(data)

        override fun encodedSize() = 1 + 16 + 4 + data.size

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
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(9).putLong(connectionId).put(data)

        override fun encodedSize() = 1 + 8 + data.size

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
        override fun encode(buf: ByteBuffer): ByteBuffer {
            val address = remoteAddr.address
            return buf.put(10).putLong(connectionId).put(address.size.toByte()).put(address)
        }

        override fun encodedSize() = 1 + 8 + 1 + remoteAddr.address.size
    }

    data class ProxyDisconnect(val connectionId: Long) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(11).putLong(connectionId)

        override fun encodedSize() = 1 + 8
    }

    data class ConnectionInfo(
        val connectionId: ConnectionId,
        val baseIp: String,
        val basePort: Int,
        val userIp: String,
        val protocolVersion: Int
    ) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer =
            buf.put(12)
                .putCid(connectionId)
                .putString(baseIp)
                .putShort(basePort.toShort())
                .putString(userIp)
                .putInt(protocolVersion)

        override fun encodedSize() = 1 + 8 + 2 + baseIp.length + 2 + 2 + userIp.length + 4
    }

    data class ExternalProxyServer(
        val host: String,
        val port: Int,
        val baseAddr: String,
        val mcPort: Int
    ) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer =
            buf.put(13)
                .putString(host)
                .putShort(port.toShort())
                .putString(baseAddr)
                .putShort(mcPort.toShort())

        override fun encodedSize() = 1 + 2 + host.length + 2 + 2 + baseAddr.length + 2
    }

    data class OutdatedWorldHost(val recommendedVersion: String) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(14).putString(recommendedVersion)

        override fun encodedSize() = 1 + 2 + recommendedVersion.length
    }
}
