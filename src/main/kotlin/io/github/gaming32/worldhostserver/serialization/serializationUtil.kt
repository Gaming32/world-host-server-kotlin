package io.github.gaming32.worldhostserver.serialization

import java.nio.ByteBuffer

fun String.utf8Length() = sumOf {
    @Suppress("USELESS_CAST")
    when {
        it.code <= 0x7f -> 1
        it.code <= 0x7ff || it.isSurrogate() -> 2 // Surrogate pair adds up to 4, which is correct
        else -> 3
    } as Int
}

fun PacketSerializable.toByteBuf() = encode(ByteBuffer.allocate(encodedLength())).also(ByteBuffer::flip)
