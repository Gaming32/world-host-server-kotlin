package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.ConnectionId.Companion.toConnectionId
import io.github.gaming32.worldhostserver.util.cast
import io.github.gaming32.worldhostserver.util.isSimpleDisconnectException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.intellij.lang.annotations.Language
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

private val logger = KotlinLogging.logger {}

suspend fun WorldHostServer.runProxyServer() = coroutineScope {
    if (config.baseAddr == null) {
        logger.info { "Proxy server disabled by request" }
        return@coroutineScope
    }
    if (EXTERNAL_SERVERS?.any { it.addr == null } == false) {
        logger.info {
            "Same-process proxy server is enabled, but it is not present in external_proxies.json. This means"
        }
        logger.info {
            "that it will be used only as a fallback if the client's best choice for external proxy goes down."
        }
    }
    logger.info { "Starting proxy server on port ${config.inJavaPort}" }
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.inJavaPort).use { serverSocket ->
        var nextConnectionId = 0L
        logger.info { "Started proxy server on ${serverSocket.localAddress}" }
        while (true) {
            val proxySocket = serverSocket.accept()
            logger.info { "Accepted proxy connection from ${proxySocket.remoteAddress}" }
            val connectionId = nextConnectionId++
            launch {
                var connection: Connection? = null
                try {
                    val receiveChannel = proxySocket.openReadChannel()
                    val sendChannel = proxySocket.openWriteChannel()

                    val handshakeData = ByteArray(receiveChannel.readVarInt()).also { receiveChannel.readFully(it) }
                    val inp = ByteArrayInputStream(handshakeData)
                    inp.readVarInt() // Packet ID
                    inp.readVarInt() // Protocol version
                    val thisAddr = inp.readString(255)
                    inp.skip(2) // Port
                    val nextState = inp.readVarInt()

                    val cidStr = thisAddr.substringBefore('.')
                    val destCid = try {
                        cidStr.toConnectionId()
                    } catch (e: Exception) {
                        if (thisAddr == config.baseAddr) {
                            // Star Trek humor
                            return@launch disconnect(sendChannel, nextState, "I'm a proxy server, not an engineer!")
                        }
                        return@launch disconnect(sendChannel, nextState, "Invalid ConnectionId: ${e.localizedMessage}")
                    }

                    connection = whConnections.byId(destCid) ?: return@launch disconnect(
                        sendChannel,
                        nextState,
                        "Couldn't find that server"
                    )

                    proxyConnections.withLock {
                        this[connectionId] = Pair(connection!!.id, sendChannel)
                    }
                    connection.sendMessage(
                        WorldHostS2CMessage.ProxyConnect(
                            connectionId,
                            proxySocket.remoteAddress.toJavaAddress().cast<InetSocketAddress>().address
                        )
                    )
                    connection.sendMessage(
                        WorldHostS2CMessage.ProxyC2SPacket(
                            connectionId,
                            ByteArrayOutputStream().apply {
                                writeVarInt(handshakeData.size)
                                write(handshakeData)
                            }.toByteArray()
                        )
                    )
                    val buffer = ByteArray(64 * 1024)
                    proxyLoop@ while (!sendChannel.isClosedForWrite) {
                        if (!connection!!.open) {
                            sendChannel.flushAndClose()
                            break
                        }
                        val n = receiveChannel.readAvailable(buffer)
                        if (n == 0) continue
                        if (n == -1) {
                            sendChannel.flushAndClose()
                            break
                        }
                        if (!connection.open) {
                            val failureStart = System.currentTimeMillis()
                            do {
                                if ((System.currentTimeMillis() - failureStart) > 5000) {
                                    sendChannel.flushAndClose()
                                    break@proxyLoop
                                }
                                yield()
                                connection = whConnections.byId(destCid)
                            } while (connection == null || !connection.open)
                        }
                        connection.sendMessage(
                            WorldHostS2CMessage.ProxyC2SPacket(
                                connectionId, buffer.copyOf(n)
                            )
                        )
                    }
                } catch (_: ClosedReceiveChannelException) {
                } catch (e: Exception) {
                    if (!e.isSimpleDisconnectException) {
                        logger.error(e) { "An error occurred in proxy client handling" }
                    }
                } finally {
                    proxyConnections.withLock { this -= connectionId }
                    if (connection?.open == true) {
                        connection.sendMessage(WorldHostS2CMessage.ProxyDisconnect(connectionId))
                    }
                    logger.info { "Proxy connection closed" }
                }
            }
        }
    }
}

private suspend fun disconnect(sendChannel: ByteWriteChannel, nextState: Int, message: String) {
    @Language("JSON") val jsonMessage = """{"text":"$message","color":"red"}"""
    val out = ByteArrayOutputStream()
    out.writeVarInt(0x00)
    if (nextState == 1) {
        //language=JSON
        out.writeString("""{"description":$jsonMessage}""")
    } else if (nextState == 2) {
        out.writeString(jsonMessage, 262144)
    }
    val out2 = ByteArrayOutputStream()
    out2.writeVarInt(out.size())
    @Suppress("BlockingMethodInNonBlockingContext")
    out2.write(out.toByteArray())
    sendChannel.writeFully(out2.toByteArray())
    sendChannel.flush()

    if (nextState == 1) {
        out.reset()
        out.writeVarInt(0x01)
        repeat(8) {
            out.write(0)
        }
        out2.reset()
        out2.writeVarInt(out.size())
        @Suppress("BlockingMethodInNonBlockingContext")
        out2.write(out.toByteArray())
        sendChannel.writeFully(out2.toByteArray())
        sendChannel.flush()
    }

    sendChannel.flushAndClose()
}
