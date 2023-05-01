package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.*

const val PROTOCOL_VERSION = 3
val SUPPORTED_PROTOCOLS = 2..PROTOCOL_VERSION

@OptIn(ExperimentalSerializationApi::class)
val EXTERNAL_SERVERS = File("external_proxies.json")
    .let { if (it.exists()) it else null }
    ?.inputStream()
    ?.let { Json.decodeFromStream<List<ExternalProxy>>(it) }

private val logger = KotlinLogging.logger {}

data class IdsPair(val userId: UUID, val connectionId: ConnectionId)

suspend fun WorldHostServer.startMainServer() = coroutineScope {
    logger.info("Starting WH server on port {}", config.port)
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.port).use { serverSocket ->
        logger.info("Started WH server on {}", serverSocket.localAddress)
        while (true) {
            val clientSocket = serverSocket.accept()
            launch {
                val socket = SocketWrapper(clientSocket)
                var connection: Connection? = null
                try {
                    val remoteAddr = clientSocket.remoteAddress.toJavaAddress().address
                    val protocolVersion = try {
                        socket.readChannel.readInt()
                    } catch (_: ClosedReceiveChannelException) {
                        logger.info("Received a ping connection (immediate disconnect)")
                        return@launch
                    }

                    if (protocolVersion !in SUPPORTED_PROTOCOLS) {
                        return@launch socket.closeError("Unsupported protocol version $protocolVersion")
                    }

                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION") // I have several questions
                    connection = try {
                        Connection(
                            IdsPair(
                                UUID(socket.readChannel.readLong(), socket.readChannel.readLong()),
                                ConnectionId(socket.readChannel.readLong())
                            ),
                            remoteAddr, socket, protocolVersion
                        )
                    } catch (e: Exception) {
                        logger.warn("Invalid handshake from {}", remoteAddr, e)
                        return@launch socket.closeError("Invalid handshake: $e")
                    }!!

                    logger.info("Connection opened: {}", connection)
                    if (protocolVersion < PROTOCOL_VERSION) {
                        logger.warn(
                            "Client {} has an older client! Client version: {}. Server version: {}.",
                            connection.id, protocolVersion, PROTOCOL_VERSION
                        )
                    }

                    launch requestCountry@ {
                        val jsonResponse: JsonObject = httpClient.get("https://api.iplocation.net/") {
                            parameter("ip", connection.address)
                        }.body()
                        val countryCode = jsonResponse["country_code2"].castOrNull<JsonPrimitive>()?.content
                            ?: return@requestCountry logger.warn("No country code returned for {}", connection.id)
                        if (countryCode == "-") {
                            return@requestCountry logger.info("{} not in a country", connection.id)
                        }
                        val country = COUNTRIES[countryCode]
                            ?: return@requestCountry logger.warn(
                                "Invalid country code {} for {}", countryCode, connection.id
                            )
                        connection.country = country
                        EXTERNAL_SERVERS
                            ?.minBy { it.latLong.haversineDistance(country.latLong) }
                            ?.let { proxy ->
                                if (proxy.addr == null) return@let
                                connection.externalProxy = proxy
                                socket.sendMessage(WorldHostS2CMessage.ExternalProxyServer(
                                    proxy.addr, proxy.port, proxy.baseAddr, proxy.mcPort
                                ))
                            }
                    }

                    val start = System.currentTimeMillis()
                    while (!whConnections.add(connection)) {
                        val time = System.currentTimeMillis()
                        if (time - start > 500) {
                            logger.warn("ID ${connection.id} used twice. Disconnecting $connection.")
                            return@launch socket.closeError("That connection ID is taken.")
                        }
                    }

                    logger.info("There are {} open connections.", whConnections.size)

                    socket.sendMessage(WorldHostS2CMessage.ConnectionInfo(
                        connection.id, config.baseAddr ?: "", config.exJavaPort, remoteAddr, PROTOCOL_VERSION
                    ))

                    while (true) {
                        val message = try {
                            socket.recvMessage()
                        } catch (e: ClosedReceiveChannelException) {
                            break
                        } catch (e: Exception) {
                            if (socket.readChannel.isClosedForRead) {
                                // It was critical enough to close for
                                throw e
                            }
                            logger.error("Error in client WH client handling", e)
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
                    // Shouldn't this be throwing a ClosedReceiveChannelException instead?
                    if (
                        e !is IOException ||
                        e.message != "An existing connection was forcibly closed by the remote host"
                    ) {
                        logger.error("A critical error occurred in WH client handling", e)
                    }
                } finally {
                    socket.close()
                    if (connection != null) {
                        connection.open = false
                        logger.info("Connection closed: {}", connection)
                        whConnections.remove(connection)
                        logger.info("There are {} open connections.", whConnections.size)
                    }
                }
            }
        }
    }
}
