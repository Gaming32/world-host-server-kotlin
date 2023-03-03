package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.charsets.*
import io.ktor.websocket.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

private val logger = KotlinLogging.logger {}

data class ServerConfig(val port: Int, val baseAddr: String?, val javaPort: Int)

fun main(args: Array<String>) {
    val config = run {
        val parser = ArgParser("world-host-server")
        val port by parser.option(ArgType.Int, shortName = "p", description = "Port to bind to").default(9646)
        val baseAddr by parser.option(ArgType.String, shortName = "b", description = "Base address to use for proxy connections")
        val javaPort by parser.option(ArgType.Int, shortName = "J", description = "Port to use for Java Edition proxy connections").default(25565)
        parser.parse(args)
        ServerConfig(port, baseAddr, javaPort)
    }

    logger.info("Starting world-host-server with {}", config)

    val connections = ConnectionSetAsync()

    embeddedServer(Netty, port = config.port, configure = {
    }) {
        install(WebSockets) {
            pingPeriodMillis = 10_000
            contentConverter = object : WebsocketContentConverter {
                override suspend fun serialize(charset: Charset, typeInfo: TypeInfo, value: Any): Frame =
                    if (value is WorldHostS2CMessage) {
                        Frame.Binary(false, value.encode(ByteBuffer.allocate(value.encodedSize())))
                    } else {
                        throw WebsocketConverterNotFoundException()
                    }

                override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: Frame): Any =
                    when (typeInfo.type) {
                        UUID::class -> content.buffer.uuid
                        WorldHostC2SMessage::class -> try {
                            WorldHostC2SMessage.decode(content.buffer)
                        } catch (e: IllegalArgumentException) {
                            throw WebsocketDeserializeException(e.message ?: "", e, content)
                        }
                        else -> throw WebsocketConverterNotFoundException()
                    }

                override fun isApplicable(frame: Frame) = frame.frameType == FrameType.BINARY
            }
        }
        routing {
            webSocket {
                // Can't believe Ktor makes this so difficult
//                val remoteAddr = ((call as RoutingApplicationCall).engineCall as NettyApplicationCall)
//                    .context.pipeline().channel().remoteAddress()
                val remoteAddr = call.cast<RoutingApplicationCall>()
                    .engineCall
                    .cast<NettyApplicationCall>()
                    .context
                    .pipeline()
                    .channel()
                    .remoteAddress()
                    .cast<InetSocketAddress>()
                    .address
                val connection = Connection(
                    UUID.randomUUID(),
                    remoteAddr,
                    try {
                        receiveDeserialized()
                    } catch (e: Exception) {
                        logger.warn("Invalid handshake from {}", remoteAddr, e)
                        close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Invalid handshake: $e"))
                        return@webSocket
                    },
                    this
                )
                logger.info("Connection opened: {}.", connection)
                connections.add(connection)
                try {
                    while (true) {
                        val message = try {
                            receiveDeserialized<WorldHostC2SMessage>()
                        } catch (e: WebsocketDeserializeException) {
                            logger.error("Failed to deserialize message", e)
                            sendSerialized(WorldHostS2CMessage.Error(e.message ?: ""))
                            continue
                        } catch (e: ClosedReceiveChannelException) {
                            break
                        }
                        if (logger.isDebugEnabled) {
                            logger.debug("Received message {}", message)
                        }
                        with(message) {
                            handle(config, connections, connection)
                        }
                    }
                } finally {
                    connections.remove(connection)
                }
            }
        }
    }.start(wait = true)
}
