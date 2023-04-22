package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.util.*

val SUPPORTED_PROTOCOLS = 2..2

private val logger = KotlinLogging.logger {}

data class IdsPair(val userId: UUID, val connectionId: ConnectionId)

fun WorldHostServer.startMainServer() {
    logger.info("Starting WH server on port {}", config.port)
    val coroutine: suspend CoroutineScope.() -> Unit = {
        aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.port).use { serverSocket ->
            while (true) {
                val clientSocket = serverSocket.accept()
                launch {
                    val socket = SocketWrapper(clientSocket)

                    val remoteAddr = clientSocket.remoteAddress.toJavaAddress().address
                    val protocolVersion = socket.readChannel.readInt()

                    if (protocolVersion !in SUPPORTED_PROTOCOLS) {
                        return@launch socket.closeError("Unsupported protocol version $protocolVersion")
                    }

                    val connection = try {
                        Connection(
                            IdsPair(
                                UUID(socket.readChannel.readLong(), socket.readChannel.readLong()),
                                ConnectionId(socket.readChannel.readLong())
                            ),
                            remoteAddr, socket
                        )
                    } catch (e: Exception) {
                        logger.warn("Invalid handshake from {}", remoteAddr, e)
                        return@launch socket.closeError("Invalid handshake: $e")
                    }

                    logger.info("Connection opened: {}", connection)
                    launch requestCountry@ {
                        val jsonResponse: JsonObject = httpClient.get("https://api.iplocation.net/") {
                            parameter("ip", connection.address)
                        }.body()
                        val countryCode = jsonResponse["country_code2"].castOrNull<JsonPrimitive>()?.content
                        if (countryCode == null) {
                            logger.warn("No country code returned")
                            return@requestCountry
                        }
                        if (countryCode !in VALID_COUNTRY_CODES) {
                            logger.warn("Invalid country code {}", countryCode)
                            return@requestCountry
                        }
                        connection.country = countryCode
                    }

                    whConnections.add(connection)?.let {
                        logger.warn("Connection $it and $connection conflict! Disconnecting $it.")
                        it.socket.closeError("Your connection was replaced.")
                    }

                    logger.info("There are {} open connections.", whConnections.size)

                    try {
                        socket.sendMessage(WorldHostS2CMessage.ConnectionInfo(
                            connection.id, config.baseAddr ?: "", config.exJavaPort
                        ))

                        while (true) {
                            val message = try {
                                socket.recvMessage()
                            } catch (e: ClosedReceiveChannelException) {
                                break
                            } catch (e: Exception) {
                                if (socket.writeChannel.isClosedForWrite) {
                                    // It was critical enough to close for
                                    throw e
                                }
                                socket.sendMessage(WorldHostS2CMessage.Error(e.message ?: e.javaClass.simpleName))
                                continue
                            }
                            if (logger.isDebugEnabled) {
                                logger.debug("Received message {}", message)
                            }
                            with(message) {
                                handle(this@startMainServer, connection)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("An error occurred in WS client handling", e)
                    } finally {
                        socket.close()
                        connection.open = false
                        logger.info("Connection closed: {}", connection)
                        whConnections.remove(connection)
                        logger.info("There are {} open connections.", whConnections.size)
                    }
                }
            }
        }
    }
    if (config.baseAddr == null) {
        runBlocking(block = coroutine)
    } else {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(block = coroutine)
    }
}

suspend fun ByteWriteChannel.sendMessage(message: WorldHostS2CMessage) {
    val size = message.encodedSize()
    writeInt(size)
    writeFully(message.encode(ByteBuffer.allocate(size)))
    flush()
}
