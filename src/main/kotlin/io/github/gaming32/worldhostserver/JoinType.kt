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

    fun toOnlineGame(connection: Connection, config: WorldHostServer.Config): WorldHostS2CMessage.OnlineGame?

    data class UPnP(val port: Int) : JoinType {
        override fun toOnlineGame(connection: Connection, config: WorldHostServer.Config) =
            WorldHostS2CMessage.OnlineGame(connection.address.hostAddress, port)
    }

    object Proxy : JoinType {
        override fun toOnlineGame(connection: Connection, config: WorldHostServer.Config) = config.baseAddr?.let {
            WorldHostS2CMessage.OnlineGame("$PROXY_SERVER_PREFIX${connection.id}.$it", config.exJavaPort)
        }

        override fun toString() = "Proxy"
    }
}
