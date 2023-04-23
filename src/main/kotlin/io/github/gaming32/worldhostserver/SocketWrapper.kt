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
    private val sendLock = Mutex()
    private val recvLock = Mutex()

    suspend fun sendMessage(message: WorldHostS2CMessage) = sendLock.withLock {
        val size = message.encodedSize()
        writeChannel.writeInt(size)
        writeChannel.writeFully(message.encode(ByteBuffer.allocate(size)).flip())
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
        if (size > 16 * 1024 * 1024) {
            readChannel.discardExact(size.toLong())
            throw IllegalArgumentException("Messages bigger than 16 MB are currently not allowed. We are working to remove this restriction.")
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
            sendMessage(WorldHostS2CMessage.Error(message))
        } catch (e: Exception) {
            logger.warn("Error in error sending (message \"{}\")", message, e)
        }
        close()
    }
}