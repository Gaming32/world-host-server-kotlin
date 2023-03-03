package io.github.gaming32.worldhostserver

import java.nio.ByteBuffer

sealed interface JoinType {
    companion object {
        fun decode(buf: ByteBuffer) = when (val joinTypeId = buf.byte.toUByte().toInt()) {
            0 -> UPnP(buf.short.toUShort().toInt())
            1 -> Proxy
            else -> throw IllegalArgumentException("Received packet with unknown join_type_id from client: $joinTypeId")
        }
    }

    data class UPnP(val port: Int) : JoinType

    object Proxy : JoinType {
        override fun toString() = "Proxy"
    }
}
