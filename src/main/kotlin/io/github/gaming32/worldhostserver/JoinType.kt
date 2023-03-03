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

    fun toOnlineGame(connection: Connection, config: ServerConfig): WorldHostS2CMessage.OnlineGame?

    data class UPnP(val port: Int) : JoinType {
        override fun toOnlineGame(connection: Connection, config: ServerConfig) =
            WorldHostS2CMessage.OnlineGame(connection.address.hostAddress, port)
    }

    object Proxy : JoinType {
        override fun toOnlineGame(connection: Connection, config: ServerConfig) = config.baseAddr?.let {
            WorldHostS2CMessage.OnlineGame("connect0000-${connection.id}.$it", config.javaPort)
        }

        override fun toString() = "Proxy"
    }
}
