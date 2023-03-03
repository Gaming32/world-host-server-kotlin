package io.github.gaming32.worldhostserver

import java.nio.ByteBuffer
import java.util.UUID

sealed interface WorldHostC2SMessage {
    companion object {
        fun decode(buf: ByteBuffer) = when (val typeId = buf.byte.toUByte().toInt()) {
            0 -> ListOnline(List(buf.int) { buf.uuid })
            1 -> FriendRequest(buf.uuid)
            2 -> PublishedWorld(List(buf.int) { buf.uuid })
            3 -> ClosedWorld(List(buf.int) { buf.uuid })
            4 -> RequestJoin(buf.uuid)
            5 -> try {
                JoinGranted(buf.uuid, JoinType.decode(buf))
            } catch (e: IllegalArgumentException) {
                ErrorMarker(e.message!!)
            }
            6 -> QueryRequest(buf.uuid)
            7 -> QueryResponse(buf.uuid, ByteArray(buf.int).also(buf::get))
            else -> ErrorMarker("Received packet with unknown type_id from client: $typeId")
        }
    }

    data class ErrorMarker(val message: String) : WorldHostC2SMessage

    data class ListOnline(val friends: Collection<UUID>) : WorldHostC2SMessage

    data class FriendRequest(val toUser: UUID) : WorldHostC2SMessage

    data class PublishedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage

    data class ClosedWorld(val friends: Collection<UUID>) : WorldHostC2SMessage

    data class RequestJoin(val friend: UUID) : WorldHostC2SMessage

    data class JoinGranted(val connectionId: UUID, val joinType: JoinType) : WorldHostC2SMessage

    data class QueryRequest(val friend: UUID) : WorldHostC2SMessage

    data class QueryResponse(val connectionId: UUID, val data: ByteArray) : WorldHostC2SMessage {
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
