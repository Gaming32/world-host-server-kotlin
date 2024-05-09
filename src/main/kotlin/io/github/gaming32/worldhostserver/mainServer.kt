package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.ratelimit.RateLimitBucket
import io.github.gaming32.worldhostserver.ratelimit.RateLimited
import io.github.gaming32.worldhostserver.ratelimit.RateLimiter
import io.github.gaming32.worldhostserver.util.COMPRESSED_GEOLITE_CITY_FILES
import io.github.gaming32.worldhostserver.util.IpInfoMap
import io.github.oshai.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

const val PROTOCOL_VERSION = 5
val SUPPORTED_PROTOCOLS = 2..PROTOCOL_VERSION
val PROTOCOL_VERSION_MAP = mapOf(
    2 to "0.3.2",
    3 to "0.3.4",
    4 to "0.4.3",
    5 to "0.4.4",
)
val VERSION_NAME = PROTOCOL_VERSION_MAP[PROTOCOL_VERSION]!!

@OptIn(ExperimentalSerializationApi::class)
val EXTERNAL_SERVERS = File("external_proxies.json")
    .let { if (it.exists()) it else null }
    ?.inputStream()
    ?.let { Json.decodeFromStream<List<ExternalProxy>>(it) }

private val logger = KotlinLogging.logger {}

data class IdsPair(val userId: UUID, val connectionId: ConnectionId)

suspend fun WorldHostServer.runMainServer() = coroutineScope {
    if (!SUPPORTED_PROTOCOLS.all(PROTOCOL_VERSION_MAP::containsKey)) {
        throw AssertionError(
            "PROTOCOL_VERSION_MAP missing the following keys: " +
                (SUPPORTED_PROTOCOLS.toSet() - PROTOCOL_VERSION_MAP.keys).joinToString()
        )
    }

    @OptIn(ExperimentalTime::class)
    val ipInfoMap = run {
        logger.info("Downloading IP info map...")
        val (ipInfoMap, time) = measureTimedValue {
            IpInfoMap.loadFromCompressedGeoliteCityFiles(*COMPRESSED_GEOLITE_CITY_FILES.toTypedArray())
        }
        logger.info("Downloaded IP info map in $time")
        ipInfoMap
    }

    logger.info("Starting WH server on port {}", config.port)
    val rateLimiter = RateLimiter<InetAddress>(
        RateLimitBucket("perMinute", 20, 60.seconds),
        RateLimitBucket("perHour", 400, 60.minutes),
    )
    launch {
        while (true) {
            delay(60.seconds)
            rateLimiter.pumpLimits(32)
        }
    }
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.port).use { serverSocket ->
        logger.info("Started WH server on {}", serverSocket.localAddress)
        while (true) {
            val clientSocket = serverSocket.accept()
            launch {
                val socket = SocketWrapper(clientSocket)
                var connection: Connection? = null
                try {
                    val addrObj = clientSocket.remoteAddress.toJavaAddress() as InetSocketAddress
                    val remoteAddr = addrObj.hostString!!
                    try {
                        rateLimiter.ratelimit(addrObj.address)
                    } catch (rateLimited: RateLimited) {
                        logger.warn("$remoteAddr is reconnecting too quickly! ${rateLimited.message}")
                        return@launch socket.closeError("Ratelimit exceeded! ${rateLimited.message}")
                    }

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

                    socket.sendMessage(WorldHostS2CMessage.ConnectionInfo(
                        connection.id,
                        config.baseAddr ?: "",
                        config.exJavaPort,
                        remoteAddr,
                        PROTOCOL_VERSION,
                        config.punchPort
                    ))

                    if (protocolVersion < PROTOCOL_VERSION) {
                        logger.warn(
                            "Client {} has an older client! Client version: {}. Server version: {}.",
                            connection.id, protocolVersion, PROTOCOL_VERSION
                        )
                        socket.sendMessage(WorldHostS2CMessage.OutdatedWorldHost(VERSION_NAME))
                    }

                    run requestCountry@ {
                        val ipInfo = ipInfoMap[addrObj.address] ?: return@requestCountry
                        connection.country = ipInfo.country
                        EXTERNAL_SERVERS
                            ?.minBy { it.latLong.haversineDistance(ipInfo.latLong) }
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
                        val other = whConnections.byId(connection.id)
                        if (other?.address == connection.address) {
                            other.socket.closeError("Connection ID taken by same IP.")
                            whConnections.add(connection, true)
                            break
                        }
                        val time = System.currentTimeMillis()
                        if (time - start > 500) {
                            logger.warn("ID ${connection.id} used twice. Disconnecting $connection.")
                            return@launch socket.closeError("That connection ID is taken.")
                        }
                        yield()
                    }

                    logger.info("There are {} open connections.", whConnections.size)

                    run {
                        val received = receivedFriendRequests.withLock { remove(connection.userUuid) } ?: return@run
                        rememberedFriendRequests.withLock {
                            received.forEach { receivedFrom ->
                                socket.sendMessage(WorldHostS2CMessage.FriendRequest(receivedFrom))
                                this[receivedFrom]?.let {
                                    it -= connection.userUuid
                                    if (it.isEmpty()) {
                                        remove(receivedFrom)
                                    }
                                }
                            }
                        }
                    }

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
                            handle(this@runMainServer, connection)
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
                        with(WorldHostC2SMessage.ClosedWorld(connection.openToFriends.toList())) {
                            handle(this@runMainServer, connection)
                        }
                        logger.info("There are {} open connections.", whConnections.size)
                    }
                }
            }
        }
    }
}
