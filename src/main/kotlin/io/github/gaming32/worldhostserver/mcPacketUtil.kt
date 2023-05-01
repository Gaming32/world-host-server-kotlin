package io.github.gaming32.worldhostserver

import io.ktor.utils.io.*
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

private const val VARINT_SEGMENT_BITS = 0x7f
private const val VARINT_CONTINUE_BIT = 0x80

private fun InputStream.readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
    if (len < 0) throw IndexOutOfBoundsException()
    var n = 0
    while (n < len) {
        val count = read(b, off + n, len - n)
        if (count < 0) throw EOFException()
        n += count
    }
}

private fun InputStream.readByte(): Byte {
    val result = read()
    if (result < 0) {
        throw EOFException()
    }
    return result.toByte()
}

private fun InputStream.readUByte() = readByte().toUByte()

fun InputStream.readVarInt(): Int {
    var value = 0
    var position = 0

    while (true) {
        val currentByte = readUByte().toInt()
        value = value or (currentByte and VARINT_SEGMENT_BITS shl position)

        if ((currentByte and VARINT_CONTINUE_BIT) == 0) break

        position += 7

        if (position >= 32) throw RuntimeException("VarInt is too big")
    }

    return value
}

fun InputStream.readString(maxLength: Int = 32767): String {
    val length = readVarInt()
    if (length > maxLength) {
        throw IllegalArgumentException("String exceeds maxLength ($maxLength bytes)")
    }
    val result = ByteArray(length)
    readFully(result)
    return result.decodeToString()
}

suspend fun ByteReadChannel.readVarInt(): Int {
    var value = 0
    var position = 0

    while (true) {
        val currentByte = readByte().toUByte().toInt()
        value = value or (currentByte and VARINT_SEGMENT_BITS shl position)

        if ((currentByte and VARINT_CONTINUE_BIT) == 0) break

        position += 7

        if (position >= 32) throw RuntimeException("VarInt is too big")
    }

    return value
}

fun OutputStream.writeVarInt(i: Int) {
    var value = i
    while (true) {
        if ((value and VARINT_SEGMENT_BITS.inv()) == 0) {
            return write(value)
        }

        write(value and VARINT_SEGMENT_BITS or VARINT_CONTINUE_BIT)

        value = value ushr 7
    }
}

fun OutputStream.writeString(s: String, maxLength: Int = 32767) {
    val encoded = s.encodeToByteArray()
    if (encoded.size > maxLength) {
        throw IllegalArgumentException("String exceeds maxLength ($maxLength bytes)")
    }
    writeVarInt(encoded.size)
    write(encoded)
}
