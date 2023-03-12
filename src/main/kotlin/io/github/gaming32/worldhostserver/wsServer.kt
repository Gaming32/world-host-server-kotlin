package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.charsets.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.util.*

private val logger = KotlinLogging.logger {}

fun WorldHostServer.startWsServer() {
    logger.info("Starting WS server on port {}", config.port)
    embeddedServer(Netty, port = config.port) {
        install(WebSockets) {
            timeoutMillis = Long.MAX_VALUE
            contentConverter = object : WebsocketContentConverter {
                override suspend fun serialize(charset: Charset, typeInfo: TypeInfo, value: Any): Frame =
                    if (value is WorldHostS2CMessage) {
                        Frame.Binary(true, ByteBuffer.allocate(value.encodedSize()).also {
                            value.encode(it)
                            it.flip()
                        })
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
        install(XForwardedHeaders) {
            useLastProxy()
        }
        install(AutoHeadResponse)
        routing {
            get {
                call.respondText("This server appears to be working!")
            }
            webSocket {
                // Can't believe Ktor makes this so difficult
                val remoteAddr = call.request.origin.remoteHost
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
                logger.info("Connection opened: {}", connection)
                launch {
                    val jsonResponse: JsonObject = httpClient.get("https://api.iplocation.net/") {
                        parameter("ip", connection.address)
                    }.body()
                    val countryCode = jsonResponse["country_code2"].castOrNull<JsonPrimitive>()?.content
                    if (countryCode == null) {
                        logger.warn("No country code returned")
                        return@launch
                    }
                    if (countryCode !in VALID_COUNTRY_CODES) {
                        logger.warn("Invalid country code {}", countryCode)
                        return@launch
                    }
                    connection.country = countryCode
                }
                wsConnections.add(connection)
                logger.info("There are {} open connections.", wsConnections.size)
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
                            handle(this@startWsServer, connection)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("An error occurred in WS client handling", e)
                } finally {
                    connection.open = false
                    logger.info("Connection closed: {}", connection)
                    wsConnections.remove(connection)
                    logger.info("There are {} open connections.", wsConnections.size)
                }
            }
        }
    }.start(wait = config.baseAddr == null)
}
