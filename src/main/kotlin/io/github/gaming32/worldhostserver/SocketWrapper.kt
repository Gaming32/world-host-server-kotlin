package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.byte
import io.github.gaming32.worldhostserver.serialization.toByteBuf
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.io.readAtMostTo
import java.nio.ByteBuffer
import javax.crypto.Cipher

private val logger = KotlinLogging.logger {}

class SocketWrapper(socket: Socket) {
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel()
    private val sendLock = Mutex()
    private val recvLock = Mutex()

    suspend fun sendMessage(message: WorldHostS2CMessage, encryptCipher: Cipher? = null) = sendLock.withLock {
        val bb = message.toByteBuf()
        val outBuf = ByteBuffer.allocate(bb.remaining() + 5)
        outBuf.putInt(bb.remaining() + 1)
        outBuf.put(message.typeId)
        outBuf.put(bb)
        outBuf.flip()
        if (encryptCipher != null) {
            encryptCipher.update(outBuf.duplicate(), outBuf)
            outBuf.flip()
        }
        writeChannel.writeFully(outBuf)
        writeChannel.flush()
    }

    suspend fun recvMessage(
        decryptCipher: Cipher? = null,
        maxProtocolVersion: Int? = null
    ) = recvLock.withLock {
        val size = readChannel.readEncrypted(4, decryptCipher).int
        if (size == 0) {
            "Message is empty".let {
                closeError(it)
                throw IllegalArgumentException(it)
            }
        }
        if (size > 2 * 1024 * 1024) {
            readChannel.discardExact(size.toLong())
            throw IllegalArgumentException("Messages bigger than 2 MB are not allowed.")
        }
        val data = readChannel.readEncrypted(size, decryptCipher)
        val typeId = data.byte.toUByte().toInt()
        WorldHostC2SMessage.decode(typeId, data, maxProtocolVersion)
    }

    suspend fun close() = writeChannel.flushAndClose()

    suspend fun closeError(message: String) {
        try {
            sendMessage(WorldHostS2CMessage.Error(message, true), null)
        } catch (e: Exception) {
            logger.warn(e) { "Error in critical error sending (message \"$message\")" }
        }
        close()
    }
}

private suspend fun ByteReadChannel.readEncrypted(length: Int, decryptCipher: Cipher?): ByteBuffer {
    val buffer = ByteBuffer.allocate(length)
    readFully(buffer)
    buffer.flip()
    if (decryptCipher != null) {
        decryptCipher.update(buffer.duplicate(), buffer)
        buffer.flip()
    }
    return buffer
}

// TODO: Remove when readFully from Ktor is fixed
@OptIn(InternalAPI::class)
private suspend fun ByteReadChannel.readFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        if (availableForRead == 0) {
            awaitContent()
            yield() // WH: Actually yield to avoid blocking
        }
        readBuffer.readAtMostTo(buffer)
    }
}
