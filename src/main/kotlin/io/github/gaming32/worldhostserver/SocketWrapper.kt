package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

private val logger = KotlinLogging.logger {}

class SocketWrapper(val socket: Socket) {
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel()
    val lock = Mutex()

    suspend fun sendMessage(message: WorldHostS2CMessage) = lock.withLock(this) {
        val size = message.encodedSize()
        writeChannel.writeInt(size)
        writeChannel.writeFully(message.encode(ByteBuffer.allocate(size)))
        writeChannel.flush()
    }

    suspend fun recvMessage() = lock.withLock(this) {
        val size = readChannel.readInt()
        if (size < 0) {
            "Message size is less than 0".let {
                closeError(it)
                throw IllegalArgumentException(it)
            }
        }
        if (size > 16 * 1024 * 1024) {
            readChannel.discardExact(size.toLong())
            throw IllegalArgumentException("Messages bigger than 16 MB are currently not allowed. We are working to remove this restriction.")
        }
        WorldHostC2SMessage.decode(ByteBuffer.allocate(size).also { readChannel.readFully(it) })
    }

    suspend fun close() = lock.withLock(this) { writeChannel.close() }

    suspend fun closeError(message: String) = lock.withLock(this) {
        try {
            sendMessage(WorldHostS2CMessage.Error(message))
        } catch (e: Exception) {
            logger.warn("Error in error sending (message \"{}\")", message, e)
        }
        close()
        Unit
    }
}
