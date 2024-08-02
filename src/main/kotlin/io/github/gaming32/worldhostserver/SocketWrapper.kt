package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.toByteBuf
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher

private val logger = KotlinLogging.logger {}

class SocketWrapper(socket: Socket) {
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel()
    private val sendLock = Mutex()
    private val recvLock = Mutex()

    suspend fun sendMessage(message: WorldHostS2CMessage, encryptCipher: Cipher? = null) = sendLock.withLock {
        val bb = message.toByteBuf()
        var data = ByteArray(bb.remaining()).also(bb::get)
        if (message.isEncrypted) {
            data = checkNotNull(encryptCipher) {
                "Attempted to send encrypted message $message without a cipher"
            }.update(data)
        }
        writeChannel.writeInt(data.size + 1)
        writeChannel.writeByte(message.typeId)
        writeChannel.writeFully(data)
        writeChannel.flush()
    }

    suspend fun recvMessage(decryptCipher: Cipher? = null) = recvLock.withLock {
        val size = readChannel.readInt() - 1
        if (size < 0) {
            "Message is empty".let {
                closeError(it)
                throw IllegalArgumentException(it)
            }
        }
        if (size > 2 * 1024 * 1024) {
            readChannel.discardExact(size.toLong())
            throw IllegalArgumentException("Messages bigger than 2 MB are not allowed.")
        }
        val typeId = readChannel.readByte().toUByte().toInt()
        val data = ByteArray(size).also { readChannel.readFully(it) }
        WorldHostC2SMessage.decode(typeId, data, decryptCipher)
    }

    fun close() = writeChannel.close()

    suspend fun closeError(message: String) {
        try {
            sendMessage(WorldHostS2CMessage.Error(message, true), null)
        } catch (e: Exception) {
            logger.warn(e) { "Error in critical error sending (message \"$message\")" }
        }
        close()
    }
}
