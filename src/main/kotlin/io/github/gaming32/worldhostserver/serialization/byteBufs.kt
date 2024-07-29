package io.github.gaming32.worldhostserver.serialization

import io.github.gaming32.worldhostserver.ConnectionId
import io.github.gaming32.worldhostserver.PunchCookie
import java.nio.ByteBuffer
import java.util.*

val ByteBuffer.byte inline get() = get()

val ByteBuffer.uuid get() = UUID(long, long)

val ByteBuffer.cid get() = ConnectionId(long)

val ByteBuffer.string get() = ByteArray(short.toUShort().toInt()).also(::get).decodeToString()

val ByteBuffer.punchCookie get() = PunchCookie(ByteArray(PunchCookie.BYTES).also(::get))

fun ByteBuffer.putString(s: String): ByteBuffer = putShort(s.length.toShort()).put(s.encodeToByteArray())

fun ByteBuffer.putUuid(uuid: UUID): ByteBuffer = putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)

fun ByteBuffer.putCid(connectionId: ConnectionId): ByteBuffer = putLong(connectionId.id)
