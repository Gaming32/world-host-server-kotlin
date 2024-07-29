package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.PacketSerializable
import java.nio.ByteBuffer

class PunchCookie(private val cookie: ByteArray) : PacketSerializable {
    private var hashCode = 0

    companion object {
        const val BITS = 128
        const val BYTES = BITS / 8
    }

    init {
        require(cookie.size == BYTES) {
            "PunchCookie data length must be $BITS bits"
        }
    }

    override fun encodedLength() = BYTES

    override fun encode(buf: ByteBuffer): ByteBuffer = buf.put(cookie)

    override fun toString() = cookie.toHexString()

    override fun equals(other: Any?) = other is PunchCookie && cookie.contentEquals(other.cookie)

    override fun hashCode(): Int {
        if (hashCode != 0) {
            return hashCode
        }
        var h = cookie.contentHashCode()
        if (h == 0) {
            h = 31
        }
        hashCode = h
        return h
    }
}
