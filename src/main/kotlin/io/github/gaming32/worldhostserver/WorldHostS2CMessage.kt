package io.github.gaming32.worldhostserver

import java.nio.ByteBuffer
import java.util.UUID

sealed interface WorldHostS2CMessage {
    fun encode(buf: ByteBuffer): ByteBuffer

    data class Error(val message: String) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(0).putString(message)
    }

    data class IsOnlineTo(val user: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(1).putUuid(user)
    }

    data class OnlineGame(val host: String, val port: Int) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(2).putString(host).putShort(port.toShort())
    }

    data class FriendRequest(val fromUser: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(3).putUuid(fromUser)
    }

    data class PublishedWorld(val user: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(4).putUuid(user)
    }

    data class ClosedWorld(val user: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(5).putUuid(user)
    }

    data class RequestJoin(val user: UUID, val connectionId: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(6).putUuid(user).putUuid(connectionId)
    }

    data class QueryRequest(val friend: UUID, val connectionId: UUID) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer) = buf.put(7).putUuid(friend).putUuid(connectionId)
    }

    data class QueryResponse(val friend: UUID, val data: ByteArray) : WorldHostS2CMessage {
        override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(8).putUuid(friend).putInt(data.size).put(data)

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
}
