package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.PacketSerializable
import java.nio.ByteBuffer
import java.util.*

enum class SecurityLevel : PacketSerializable {
    INSECURE, OFFLINE, SECURE;

    companion object {
        fun from(uuid: UUID, secureAuth: Boolean = true) = when {
            !secureAuth -> INSECURE
            uuid.version() != 4 -> OFFLINE
            else -> SECURE
        }
    }

    override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(ordinal.toByte())

    override fun encodedLength() = 1
}
