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
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val parser = ArgParser("world-host-server")
    val port by parser.option(ArgType.Int, shortName = "p", description = "Port to bind to").default(9646)
    val baseAddr by parser.option(ArgType.String, shortName = "b", description = "Base address to use for proxy connections")
    val javaPort by parser.option(ArgType.Int, shortName = "J", description = "Port to use for Java Edition proxy connections").default(25565)
    parser.parse(args)

    embeddedServer(Netty, port = port, configure = {
    }) {
        install(WebSockets) {
            pingPeriodMillis = 10_000
            contentConverter = object : WebsocketContentConverter {
                override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: Frame): Any =
                    when (typeInfo.type) {
                        UUID::class -> content.buffer.uuid
                        WorldHostC2SMessage::class -> WorldHostC2SMessage.decode(content.buffer)
                        else -> throw WebsocketConverterNotFoundException("No converter was found for websocket")
                    }

                override fun isApplicable(frame: Frame) = frame.frameType == FrameType.BINARY
            }
        }
        routing {
            webSocket {
            }
        }
    }.start(wait = true)
}
