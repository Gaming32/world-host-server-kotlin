package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.ConnectionId.Companion.toConnectionId
import io.github.oshai.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.withLock
import org.intellij.lang.annotations.Language
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.*
import kotlin.io.use

private val logger = KotlinLogging.logger {}

// Blocks forever (must be started last)
fun WorldHostServer.startProxyServer() {
    if (config.baseAddr == null) return
    logger.info("Starting proxy server")
    runBlocking {
        aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.inJavaPort).use { serverSocket ->
            var nextConnectionId = 0L
            logger.info("Started proxy server on {}", serverSocket.localAddress)
            while (true) {
                val proxySocket = serverSocket.accept()
                logger.info("Accepted proxy connection from {}", proxySocket.remoteAddress)
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
                            if (!thisAddr.endsWith(config.baseAddr)) {
                                // Star Trek humor
                                return@launch disconnect(sendChannel, nextState, "I'm a proxy server, not an engineer!")
                            }
                            return@launch disconnect(sendChannel, nextState, "Invalid ConnectionId: ${e.localizedMessage}")
                        }

                        connection = whConnections.byId(destCid) ?:
                            return@launch disconnect(sendChannel, nextState, "Couldn't find that server")

                        proxyConnectionsLock.withLock {
                            proxyConnections[connectionId] = sendChannel
                        }
                        connection.socket.sendMessage(WorldHostS2CMessage.ProxyConnect(
                            connectionId,
                            proxySocket.remoteAddress.toJavaAddress().cast<InetSocketAddress>().address
                        ))
                        connection.socket.sendMessage(WorldHostS2CMessage.ProxyC2SPacket(
                            connectionId,
                            ByteArrayOutputStream().apply {
                                writeVarInt(handshakeData.size)
                                write(handshakeData)
                            }.toByteArray()
                        ))
                        val buffer = ByteArray(64 * 1024)
                        proxyLoop@ while (!sendChannel.isClosedForWrite) {
                            if (!connection!!.open) {
                                sendChannel.close()
                                break
                            }
                            val n = receiveChannel.readAvailable(buffer)
                            if (n == 0) continue
                            if (n == -1) {
                                sendChannel.close()
                                break
                            }
                            if (!connection.open) {
                                val failureStart = System.currentTimeMillis()
                                do {
                                    if ((System.currentTimeMillis() - failureStart) > 5000) {
                                        sendChannel.close()
                                        break@proxyLoop
                                    }
                                    yield()
                                    connection = whConnections.byId(destCid)
                                } while (connection == null || !connection.open)
                            }
                            connection.socket.sendMessage(WorldHostS2CMessage.ProxyC2SPacket(
                                connectionId, buffer.copyOf(n)
                            ))
                        }
                    } catch (_: ClosedReceiveChannelException) {
                    } catch (e: Exception) {
                        logger.error("An error occurred in proxy client handling", e)
                    } finally {
                        proxyConnectionsLock.withLock {
                            proxyConnections -= connectionId
                        }
                        if (connection?.open == true) {
                            connection.socket.sendMessage(WorldHostS2CMessage.ProxyDisconnect(connectionId))
                        }
                        logger.info("Proxy connection closed")
                    }
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

    sendChannel.close()
}
