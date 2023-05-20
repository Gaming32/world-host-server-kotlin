package io.github.gaming32.worldhostserver.serialization

import io.github.gaming32.worldhostserver.cast
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*

interface PacketSerializable {
    companion object {
        private val defaults: Map<Class<out Any>, Default<out Any>> = mapOf(
            String::class.java to Default(ByteBuffer::putString) { 2 + utf8Length() },
            UUID::class.java to Default(ByteBuffer::putUuid, 16),
            Short::class.javaObjectType to Default<Short>({ putShort(it) }, 2),
            Int::class.javaObjectType to Default(ByteBuffer::putInt, 4),
            ByteArray::class.java to Default<ByteArray>(ByteBuffer::put) { size },
            Long::class.javaObjectType to Default(ByteBuffer::putLong, 8),
            InetAddress::class.java to Default<InetAddress>(
                {
                    val address = it.address
                    put(address.size.toByte()).put(address)
                },
                { 1 + address.size }
            )
        )

        fun Any.toSerializable() = if (this is PacketSerializable) {
            this
        } else {
            defaults[javaClass]
                ?.cast<Default<Any>>()
                ?.toSerializable(this)
                ?: throw IllegalArgumentException("Not serializable: $javaClass")
        }
    }

    private class Default<T>(val encoder: ByteBuffer.(T) -> ByteBuffer, val sizer: T.() -> Int) {
        constructor(encoder: ByteBuffer.(T) -> ByteBuffer, size: Int) : this(encoder, { size })

        fun toSerializable(value: T) = object : PacketSerializable {
            override fun encode(buf: ByteBuffer) = buf.encoder(value)
            override fun encodedLength() = sizer(value)
        }
    }

    fun encode(buf: ByteBuffer): ByteBuffer

    fun encodedLength(): Int
}
