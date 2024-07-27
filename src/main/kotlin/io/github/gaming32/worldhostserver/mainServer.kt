package io.github.gaming32.worldhostserver

import com.mojang.authlib.GameProfile
import com.mojang.authlib.exceptions.AuthenticationUnavailableException
import com.mojang.authlib.minecraft.MinecraftSessionService
import com.mojang.authlib.yggdrasil.ProfileResult
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import io.github.gaming32.worldhostserver.ratelimit.RateLimitBucket
import io.github.gaming32.worldhostserver.ratelimit.RateLimited
import io.github.gaming32.worldhostserver.ratelimit.RateLimiter
import io.github.gaming32.worldhostserver.util.COMPRESSED_GEOLITE_CITY_FILES
import io.github.gaming32.worldhostserver.util.IpInfoMap
import io.github.gaming32.worldhostserver.util.MinecraftCrypt
import io.github.gaming32.worldhostserver.util.isSimpleDisconnectException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyPair
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

const val PROTOCOL_VERSION = 6
const val NEW_AUTH_PROTOCOL = 6
val SUPPORTED_PROTOCOLS = 2..PROTOCOL_VERSION
val PROTOCOL_VERSION_MAP = mapOf(
    2 to "0.3.2",
    3 to "0.3.4",
    4 to "0.4.3",
    5 to "0.4.4",
    6 to "0.4.14",
)
val VERSION_NAME = PROTOCOL_VERSION_MAP[PROTOCOL_VERSION]!!

private const val KEY_PREFIX = 0xFAFA0000.toInt()

@OptIn(ExperimentalSerializationApi::class)
val EXTERNAL_SERVERS = File("external_proxies.json")
    .let { if (it.exists()) it else null }
    ?.inputStream()
    ?.let { Json.decodeFromStream<List<ExternalProxy>>(it) }

private val logger = KotlinLogging.logger {}

private data class HandshakeResult(
    val userId: UUID,
    val connectionId: ConnectionId,
    val decryptCipher: Cipher?,
    val encryptCipher: Cipher?
)

suspend fun WorldHostServer.runMainServer() = coroutineScope {
    if (!SUPPORTED_PROTOCOLS.all(PROTOCOL_VERSION_MAP::containsKey)) {
        throw AssertionError(
            "PROTOCOL_VERSION_MAP missing the following keys: " +
                (SUPPORTED_PROTOCOLS.toSet() - PROTOCOL_VERSION_MAP.keys).joinToString()
        )
    }

    val ipInfoMap = run {
        logger.info { "Downloading IP info map..." }
        val (ipInfoMap, time) = measureTimedValue {
            IpInfoMap.loadFromCompressedGeoliteCityFiles(*COMPRESSED_GEOLITE_CITY_FILES.toTypedArray())
        }
        logger.info { "Downloaded IP info map in $time" }
        ipInfoMap
    }

    val sessionService = YggdrasilAuthenticationService(Proxy.NO_PROXY).createMinecraftSessionService()
    val keyPair = run {
        logger.info { "Generating key pair" }
        MinecraftCrypt.generateKeyPair()
    }

    logger.info { "Starting WH server on port ${config.port}" }
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
        logger.info { "Started WH server on ${serverSocket.localAddress}" }
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
                        logger.warn { "$remoteAddr is reconnecting too quickly! ${rateLimited.message}" }
                        return@launch socket.closeError("Ratelimit exceeded! ${rateLimited.message}")
                    }

                    val protocolVersion = try {
                        socket.readChannel.readInt()
                    } catch (_: ClosedReceiveChannelException) {
                        logger.info { "Received a ping connection (immediate disconnect)" }
                        return@launch
                    }

                    if (protocolVersion !in SUPPORTED_PROTOCOLS) {
                        return@launch socket.closeError("Unsupported protocol version $protocolVersion")
                    }

                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION") // I have several questions
                    connection = try {
                        val handshakeResult = if (protocolVersion < NEW_AUTH_PROTOCOL) {
                            HandshakeResult(
                                socket.readChannel.readUuid(),
                                ConnectionId(socket.readChannel.readLong()),
                                null, null
                            )
                        } else {
                            performHandshake(socket, sessionService, keyPair)
                        }
                        Connection(
                            handshakeResult.connectionId,
                            remoteAddr,
                            handshakeResult.userId,
                            socket,
                            protocolVersion,
                            handshakeResult.decryptCipher,
                            handshakeResult.encryptCipher
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Invalid handshake from $remoteAddr" }
                        return@launch socket.closeError("Invalid handshake: $e")
                    }!!

                    logger.info { "Connection opened: $connection" }

                    connection.sendMessage(WorldHostS2CMessage.ConnectionInfo(
                        connection.id,
                        config.baseAddr ?: "",
                        config.exJavaPort,
                        remoteAddr,
                        PROTOCOL_VERSION,
                        config.punchPort
                    ))

                    if (protocolVersion < PROTOCOL_VERSION) {
                        logger.warn {
                            "Client ${connection.id} has an older client! " +
                                "Client version: $protocolVersion. " +
                                "Server version: $PROTOCOL_VERSION."
                        }
                        connection.sendMessage(WorldHostS2CMessage.OutdatedWorldHost(VERSION_NAME))
                    }

                    if (connection.securityLevel == SecurityLevel.INSECURE && connection.userUuid.version() == 4) {
                        // Using Error because Warning was only added in this protocol version
                        connection.sendMessage(WorldHostS2CMessage.Error(
                            "You are using an old insecure version of World Host. It is highly recommended that " +
                                "you update to ${PROTOCOL_VERSION_MAP[NEW_AUTH_PROTOCOL]} or later."
                        ))
                    }

                    run requestCountry@ {
                        val ipInfo = ipInfoMap[addrObj.address] ?: return@requestCountry
                        connection.country = ipInfo.country
                        EXTERNAL_SERVERS
                            ?.minBy { it.latLong.haversineDistance(ipInfo.latLong) }
                            ?.let { proxy ->
                                if (proxy.addr == null) return@let
                                connection.externalProxy = proxy
                                connection.sendMessage(WorldHostS2CMessage.ExternalProxyServer(
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
                            logger.warn { "ID ${connection.id} used twice. Disconnecting $connection." }
                            return@launch socket.closeError("That connection ID is taken.")
                        }
                        yield()
                    }

                    logger.info { "There are ${whConnections.size} open connections." }

                    run {
                        val received = receivedFriendRequests.withLock { remove(connection.userUuid) } ?: return@run
                        rememberedFriendRequests.withLock {
                            received.forEach { receivedFrom ->
                                connection.sendMessage(WorldHostS2CMessage.FriendRequest(
                                    receivedFrom, SecurityLevel.from(receivedFrom)
                                ))
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
                            connection.recvMessage()
                        } catch (e: ClosedReceiveChannelException) {
                            break
                        } catch (e: Exception) {
                            if (socket.readChannel.isClosedForRead) {
                                // It was critical enough to close for
                                throw e
                            }
                            logger.error(e) { "Error in WH client handling" }
                            connection.sendMessage(WorldHostS2CMessage.Error(e.message ?: e.javaClass.simpleName))
                            continue
                        }
                        logger.debug { "Received message $message" }
                        with(message) {
                            handle(this@runMainServer, connection)
                        }
                    }
                } catch (_: ClosedReceiveChannelException) {
                } catch (e: Exception) {
                    // Shouldn't this be throwing a ClosedReceiveChannelException instead?
                    if (!e.isSimpleDisconnectException) {
                        logger.error(e) { "A critical error occurred in WH client handling" }
                    }
                } finally {
                    socket.close()
                    if (connection != null) {
                        connection.open = false
                        logger.info { "Connection closed: $connection" }
                        whConnections.remove(connection)
                        with(WorldHostC2SMessage.ClosedWorld(connection.openToFriends.toList())) {
                            handle(this@runMainServer, connection)
                        }
                        logger.info { "There are ${whConnections.size} open connections." }
                    }
                }
            }
        }
    }
}

private suspend fun performHandshake(
    socket: SocketWrapper,
    sessionService: MinecraftSessionService,
    keyPair: KeyPair
): HandshakeResult {
    socket.writeChannel.writeInt(KEY_PREFIX)
    socket.writeChannel.flush()

    val encodedPublicKey = keyPair.public.encoded
    val challenge = ByteArray(16).also(SecureRandom()::nextBytes)

    socket.writeChannel.writeShort(encodedPublicKey.size.toShort())
    socket.writeChannel.writeFully(encodedPublicKey)
    socket.writeChannel.writeShort(challenge.size.toShort())
    socket.writeChannel.writeFully(challenge)
    socket.writeChannel.flush()

    val encryptedChallenge = ByteArray(socket.readChannel.readShort().toUShort().toInt())
    socket.readChannel.readFully(encryptedChallenge)

    if (!challenge.contentEquals(MinecraftCrypt.decryptUsingKey(keyPair.private, encryptedChallenge))) {
        throw IllegalStateException("Challenge failed")
    }

    val encryptedSecretKey = ByteArray(socket.readChannel.readShort().toUShort().toInt())
    socket.readChannel.readFully(encryptedSecretKey)

    val secretKey = MinecraftCrypt.decryptByteToSecretKey(keyPair.private, encryptedSecretKey)
    val authKey = BigInteger(MinecraftCrypt.digestData("", keyPair.public, secretKey)).toString(16)

    val uuid = socket.readChannel.readUuid()
    val username = socket.readChannel.readString()
    val connectionId = ConnectionId(socket.readChannel.readLong())

    val expectedUuid = when (uuid.version()) {
        4 -> {
            val profile = try {
                withContext(Dispatchers.IO) {
                    sessionService.hasJoinedServer(username, authKey, null)
                }
            } catch (_: AuthenticationUnavailableException) {
                logger.warn { "Authentication servers are down. Unable to verify $username. Will allow anyway." }
                ProfileResult(GameProfile(uuid, username))
            }
            if (profile == null) {
                throw IllegalStateException("Failed to verify username. Please restart your game and the launcher.")
            }
            profile.profile.id
        }
        3 -> UUID.nameUUIDFromBytes("OfflinePlayer:$username".encodeToByteArray())
        else -> throw IllegalArgumentException("Unsupported UUID version ${uuid.version()}")
    }
    if (uuid != expectedUuid) {
        throw IllegalStateException("Mismatched UUID. Client said $uuid. Expected $expectedUuid")
    }

    return HandshakeResult(
        uuid, connectionId,
        MinecraftCrypt.getCipher(Cipher.DECRYPT_MODE, secretKey),
        MinecraftCrypt.getCipher(Cipher.ENCRYPT_MODE, secretKey)
    )
}

private suspend fun ByteReadChannel.readString() =
    ByteArray(readShort().toUShort().toInt())
        .also { readFully(it) }
        .decodeToString()

private suspend fun ByteReadChannel.readUuid() = UUID(readLong(), readLong())
