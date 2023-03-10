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

    data class OnlineGame(val host: String, val port: Int) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(2).putString(host).putShort(port.toShort())

        override fun encodedSize() = 1 + 2 + host.length + 2
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

    data class RequestJoin(val user: UUID, val connectionId: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(6).putUuid(user).putUuid(connectionId)

        override fun encodedSize() = 1 + 16 + 16
    }

    data class QueryRequest(val friend: UUID, val connectionId: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(7).putUuid(friend).putUuid(connectionId)

        override fun encodedSize() = 1 + 16 + 16
    }

    data class QueryResponse(val friend: UUID, val data: ByteArray) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(8).putUuid(friend).putInt(data.size).put(data)

        override fun encodedSize() = 1 + 16 + 4 + data.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as QueryResponse

            if (friend != other.friend) return false
            if (!data.contentEquals(other.data)) return false

            return true
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
            if (!data.contentEquals(other.data)) return false

            return true
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
}
