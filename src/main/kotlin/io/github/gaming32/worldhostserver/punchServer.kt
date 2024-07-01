package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.util.cast
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress

private val logger = KotlinLogging.logger {}

class PunchClient(
    val punchSocket: Socket,
    val receiveChannel: ByteReadChannel,
    val sendChannel: ByteWriteChannel,
    val isServer: Boolean,
    val connectionId: ConnectionId,
    val targetConnectionId: ConnectionId
) {
    val address = punchSocket.remoteAddress.toJavaAddress().cast<InetSocketAddress>().address

    fun makeKey() = Pair(connectionId, targetConnectionId)

    suspend fun sendHostPort(host: InetAddress, connectPort: Int, localPort: Int) {
        val hostAddr = host.address
        sendChannel.writeByte(hostAddr.size)
        sendChannel.writeFully(hostAddr)
        sendChannel.writeShort(connectPort)
        sendChannel.writeShort(localPort)
        sendChannel.flush()
    }
}

suspend fun WorldHostServer.runPunchServer() = coroutineScope {
    if (config.punchPort == 0) {
        logger.info { "Punch server disabled by request" }
        return@coroutineScope
    }
    logger.info { "Starting punch server" }
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.punchPort).use { serverSocket ->
        logger.info { "Started punch server on ${serverSocket.localAddress}" }
        while (true) {
            val punchSocket = serverSocket.accept()
            logger.info { "Accepted punch connection from ${punchSocket.remoteAddress}" }
            launch {
                var key: Pair<ConnectionId, ConnectionId>? = null
                try {
                    val receiveChannel = punchSocket.openReadChannel()
                    val sendChannel = punchSocket.openWriteChannel()

                    val isServer = receiveChannel.readByte() != 0.toByte()
                    val myConnectionId = ConnectionId(receiveChannel.readLong())
                    val targetConnectionId = ConnectionId(receiveChannel.readLong())
                    val client = PunchClient(
                        punchSocket, receiveChannel, sendChannel, isServer, myConnectionId, targetConnectionId
                    )

                    key = client.makeKey()
                    val targetKey = Pair(targetConnectionId, myConnectionId)

                    waitingPunch.withLock {
                        remove(targetKey) ?: run {
                            this[key] = client
                            null
                        }
                    }?.let { otherClient ->
                        if (isServer == otherClient.isServer) {
                            throw IllegalStateException("Client-client or server-server connection!")
                        }
                        val serverClient = if (isServer) client else otherClient
                        val connectPort = serverClient.punchSocket.localAddress.toJavaAddress().port
                        val localPort = serverClient.punchSocket.localAddress.toJavaAddress().port
                        client.sendHostPort(otherClient.address, connectPort, localPort)
                        otherClient.sendHostPort(client.address, connectPort, localPort)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error in punch socket handling" }
                    punchSocket.close()
                } finally {
                    if (key != null) {
                        waitingPunch.withLock { this -= key }
                    }
                }
            }
        }
    }
}
