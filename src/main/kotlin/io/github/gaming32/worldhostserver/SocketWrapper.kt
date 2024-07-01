package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.toByteBuf
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

private val logger = KotlinLogging.logger {}

class SocketWrapper(socket: Socket) {
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel()
    private val sendLock = Mutex()
    private val recvLock = Mutex()

    suspend fun sendMessage(message: WorldHostS2CMessage) = sendLock.withLock {
        val bb = message.toByteBuf()
        writeChannel.writeInt(bb.remaining())
        writeChannel.writeFully(bb)
        writeChannel.flush()
    }

    suspend fun recvMessage() = recvLock.withLock {
        val size = readChannel.readInt()
        if (size < 0) {
            "Message size is less than 0".let {
                closeError(it)
                throw IllegalArgumentException(it)
            }
        }
        if (size > 2 * 1024 * 1024) {
            readChannel.discardExact(size.toLong())
            throw IllegalArgumentException("Messages bigger than 2 MB are not allowed.")
        }
        val bb = ByteBuffer.allocate(size)
        val read = readChannel.readFully(bb)
        if (read != size) {
            throw IllegalStateException("Mismatch in packet size! Expected $size, read $read.")
        }
        bb.flip()
        WorldHostC2SMessage.decode(bb)
    }

    fun close() = writeChannel.close()

    suspend fun closeError(message: String) {
        try {
            sendMessage(WorldHostS2CMessage.Error(message, true))
        } catch (e: Exception) {
            logger.warn(e) { "Error in critical error sending (message \"$message\")" }
        }
        close()
    }
}
