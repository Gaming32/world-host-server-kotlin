package io.github.gaming32.worldhostserver

import com.mojang.authlib.GameProfile
import com.mojang.authlib.exceptions.AuthenticationUnavailableException
import com.mojang.authlib.yggdrasil.ProfileResult
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import io.github.gaming32.worldhostserver.ratelimit.RateLimitBucket
import io.github.gaming32.worldhostserver.ratelimit.RateLimited
import io.github.gaming32.worldhostserver.ratelimit.RateLimiter
import io.github.gaming32.worldhostserver.util.*
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
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel
import java.security.KeyPair
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private const val KEY_PREFIX = 0xFAFA0000.toInt()

@OptIn(ExperimentalSerializationApi::class)
val EXTERNAL_SERVERS = File("external_proxies.json")
    .let { if (it.exists()) it else null }
    ?.inputStream()
    ?.let { Json.decodeFromStream<List<ExternalProxy>>(it) }

private val logger = KotlinLogging.logger {}
private val sessionService = YggdrasilAuthenticationService(Proxy.NO_PROXY).createMinecraftSessionService()

suspend fun WorldHostServer.runMainServer() = coroutineScope {
    // Forced initializations
    ProtocolVersions
    WorldHostC2SMessage

    val ipInfoMap = run {
        logger.info { "Downloading IP info map..." }
        val (ipInfoMap, time) = measureTimedValue {
            IpInfoMap.loadFromCompressedGeoliteCityFiles(*COMPRESSED_GEOLITE_CITY_FILES.toTypedArray())
        }
        logger.info { "Downloaded IP info map in $time" }
        ipInfoMap
    }

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
            clientSocket.castOrNull<Selectable>()
                ?.channel
                ?.castOrNull<SocketChannel>()
                ?.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
                ?: logger.warn { "Failed to enable SO_KEEPALIVE on connected socket" }
            launch {
                val socket = SocketWrapper(clientSocket)
                var connection: Connection? = null
                var handleContext: WorldHostC2SMessage.HandleContext? = null
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

                    if (protocolVersion !in ProtocolVersions.SUPPORTED) {
                        return@launch socket.closeError("Unsupported protocol version $protocolVersion")
                    }

                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION") // I have several questions
                    connection = try {
                        val handshakeResult = if (protocolVersion < ProtocolVersions.NEW_AUTH_PROTOCOL) {
                            Result.success(HandshakeResult(
                                socket.readChannel.readUuid(),
                                ConnectionId(socket.readChannel.readLong()),
                                null, null
                            ))
                        } else {
                            performHandshake(socket, keyPair, protocolVersion >= ProtocolVersions.ENCRYPTED_PROTOCOL)
                        }.getOrElse {
                            logger.warn { "Handshake from $remoteAddr failed: ${it.message}" }
                            socket.closeError("Handshake failed: ${it.message}")
                            return@launch
                        }
                        handshakeResult.warning?.let {
                            logger.warn { "Warning in handshake from $remoteAddr: $it" }
                            socket.sendMessage(WorldHostS2CMessage.Warning(it, important = false))
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
                        if (e !is ClosedReceiveChannelException && !e.isSimpleDisconnectException) {
                            logger.warn(e) { "Failed to perform handshake from $remoteAddr" }
                            socket.closeError(e.toString())
                        }
                        return@launch
                    }!!
                    handleContext = WorldHostC2SMessage.HandleContext(
                        this@launch, this@runMainServer, connection
                    )

                    logger.info { "Connection opened: $connection" }

                    val latestVisibleProtocolVersion =
                        if (protocolVersion <= ProtocolVersions.STABLE) {
                            ProtocolVersions.STABLE
                        } else {
                            ProtocolVersions.CURRENT
                        }
                    connection.sendMessage(WorldHostS2CMessage.ConnectionInfo(
                        connection.id,
                        config.baseAddr ?: "",
                        config.exJavaPort,
                        remoteAddr,
                        latestVisibleProtocolVersion,
                        0
                    ))
                    if (protocolVersion < latestVisibleProtocolVersion) {
                        logger.warn {
                            "Client ${connection.id} has an outdated client! " +
                                "Client version: $protocolVersion. " +
                                "Server version: ${ProtocolVersions.CURRENT} (stable ${ProtocolVersions.STABLE})."
                        }
                        connection.sendMessage(WorldHostS2CMessage.OutdatedWorldHost(
                            ProtocolVersions.TO_NAME.getValue(latestVisibleProtocolVersion)
                        ))
                    }

                    if (connection.securityLevel == SecurityLevel.INSECURE && connection.userUuid.version() == 4) {
                        // Using Error because Warning was only added in this protocol version
                        connection.sendMessage(WorldHostS2CMessage.Error(
                            "You are using an old insecure version of World Host. It is highly recommended that " +
                                "you update to ${ProtocolVersions.TO_NAME[ProtocolVersions.NEW_AUTH_PROTOCOL]} or later."
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
                            handleContext.handle()
                        }
                    }
                } catch (_: ClosedReceiveChannelException) {
                } catch (e: Exception) {
                    // Shouldn't this be throwing a ClosedReceiveChannelException instead?
                    if (!e.isSimpleDisconnectException) {
                        logger.error(e) { "A critical error occurred in WH client handling" }
                        socket.closeError(e.toString())
                    }
                } finally {
                    socket.close()
                    if (connection != null) {
                        connection.open = false
                        logger.info { "Connection closed: $connection" }
                        whConnections.remove(connection)
                        with(WorldHostC2SMessage.ClosedWorld(connection.openToFriends.toList())) {
                            handleContext!!.handle()
                        }
                        logger.info { "There are ${whConnections.size} open connections." }
                    }
                }
            }
        }
    }
}

private data class HandshakeResult(
    val userId: UUID,
    val connectionId: ConnectionId,
    val decryptCipher: Cipher?,
    val encryptCipher: Cipher?,
    val warning: String? = null,
)

private suspend fun performHandshake(
    socket: SocketWrapper,
    keyPair: KeyPair,
    supportsEncryption: Boolean
): Result<HandshakeResult> {
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
        return Result.failure(IllegalStateException("Challenge failed"))
    }

    val encryptedSecretKey = ByteArray(socket.readChannel.readShort().toUShort().toInt())
    socket.readChannel.readFully(encryptedSecretKey)

    val secretKey = MinecraftCrypt.decryptByteToSecretKey(keyPair.private, encryptedSecretKey)
    val authKey = BigInteger(MinecraftCrypt.digestData("", keyPair.public, secretKey)).toString(16)

    val requestedUuid = socket.readChannel.readUuid()
    val requestedUsername = socket.readChannel.readString()
    val connectionId = ConnectionId(socket.readChannel.readLong())

    val verifyResult = verifyProfile(requestedUuid, requestedUsername, authKey)
    if (verifyResult.isMismatch && verifyResult.mismatchIsError) {
        return Result.failure(IllegalStateException(verifyResult.messageWithUuidInfo()))
    }

    return Result.success(HandshakeResult(
        requestedUuid, connectionId,
        if (supportsEncryption) MinecraftCrypt.getCipher(Cipher.DECRYPT_MODE, secretKey) else null,
        if (supportsEncryption) MinecraftCrypt.getCipher(Cipher.ENCRYPT_MODE, secretKey) else null,
        warning = verifyResult.takeIf(VerifyProfileResult::isMismatch)?.messageWithUuidInfo()
    ))
}

private suspend fun ByteReadChannel.readString() =
    ByteArray(readShort().toUShort().toInt())
        .also { readFully(it) }
        .decodeToString()

private suspend fun ByteReadChannel.readUuid() = UUID(readLong(), readLong())

private data class VerifyProfileResult(
    private val requestedUuid: UUID,
    private val expectedUuid: UUID,
    private val mismatchMessage: String,
    val mismatchIsError: Boolean,
    private val includeUuidInfo: Boolean,
) {
    val isMismatch get() = requestedUuid != expectedUuid

    fun messageWithUuidInfo() =
        if (includeUuidInfo) {
            "$mismatchMessage Client gave UUID $requestedUuid. Expected UUID $expectedUuid."
        } else {
            mismatchMessage
        }
}

private suspend fun verifyProfile(
    requestedUuid: UUID,
    requestedUsername: String,
    authKey: String
) =
    if (requestedUuid.version() == 4) {
        val profile = try {
            withContext(Dispatchers.IO) {
                sessionService.hasJoinedServer(requestedUsername, authKey, null)
            }
        } catch (_: AuthenticationUnavailableException) {
            logger.warn { "Authentication servers are down. Unable to verify $requestedUsername. Will allow anyway." }
            ProfileResult(GameProfile(requestedUuid, requestedUsername))
        }
        if (profile == null) {
            VerifyProfileResult(
                requestedUuid,
                NIL_UUID,
                "Failed to verify username. " +
                    "Please restart your game and the launcher. " +
                    "If you're unable to join regular Minecraft servers, this is not a bug with World Host.",
                mismatchIsError = true,
                includeUuidInfo = false
            )
        } else {
            VerifyProfileResult(
                requestedUuid,
                profile.profile.id,
                "Mismatched UUID.",
                mismatchIsError = true,
                includeUuidInfo = true
            )
        }
    } else {
        VerifyProfileResult(
            requestedUuid,
            UUID.nameUUIDFromBytes("OfflinePlayer:$requestedUsername".encodeToByteArray()),
            "Mismatched offline UUID. Some features may not work as intended.",
            mismatchIsError = false,
            includeUuidInfo = true
        )
    }
