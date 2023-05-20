package io.github.gaming32.worldhostserver.serialization

import io.github.gaming32.worldhostserver.serialization.PacketSerializable.Companion.toSerializable
import java.nio.ByteBuffer

interface FieldedSerializer : PacketSerializable {
    val fields: List<Any>

    override fun encode(buf: ByteBuffer) = fields.fold(buf) { b, t -> t.toSerializable().encode(b) }

    override fun encodedLength() = fields.sumOf { it.toSerializable().encodedLength() }
}
