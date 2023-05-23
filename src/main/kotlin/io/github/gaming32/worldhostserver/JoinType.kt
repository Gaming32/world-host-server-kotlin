package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.byte
import java.nio.ByteBuffer

sealed interface JoinType {
    companion object {
        fun decode(buf: ByteBuffer) = when (val joinTypeId = buf.byte.toUByte().toInt()) {
            0 -> UPnP(buf.short.toUShort().toInt())
            1 -> Proxy
            2 -> Punch
            else -> throw IllegalArgumentException("Received packet with unknown join_type_id from client: $joinTypeId")
        }
    }

    fun toOnlineGame(connection: Connection, config: WorldHostServer.Config): WorldHostS2CMessage.OnlineGame?

    data class UPnP(val port: Int) : JoinType {
        override fun toOnlineGame(connection: Connection, config: WorldHostServer.Config) =
            WorldHostS2CMessage.OnlineGame(connection.address, port, connection.id)
    }

    object Proxy : JoinType {
        override fun toOnlineGame(
            connection: Connection,
            config: WorldHostServer.Config
        ): WorldHostS2CMessage.OnlineGame? {
            val baseAddr = if (connection.protocolVersion >= 3) {
                connection.externalProxy?.baseAddr
            } else {
                null
            } ?: config.baseAddr ?: return null

            val port = if (connection.protocolVersion >= 3) {
                connection.externalProxy?.mcPort
            } else {
                null
            } ?: config.exJavaPort

            return WorldHostS2CMessage.OnlineGame("${connection.id}.$baseAddr", port, connection.id)
        }

        override fun toString() = "JoinType.Proxy"
    }

    object Punch : JoinType {
        override fun toOnlineGame(
            connection: Connection,
            config: WorldHostServer.Config
        ) = if (config.punchPort <= 0) {
            null
        } else {
            WorldHostS2CMessage.OnlineGame("", 0, connection.id, true)
        }

        override fun toString() = "JoinType.Punch"
    }
}
