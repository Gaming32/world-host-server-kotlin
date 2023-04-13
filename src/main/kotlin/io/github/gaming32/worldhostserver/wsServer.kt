package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.WorldHostServerEndpoint.Encoder
import io.github.oshai.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import jakarta.websocket.*
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerEndpoint
import jakarta.websocket.server.ServerEndpointConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.jetty.http.QuotedCSV
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

private val logger = KotlinLogging.logger {}

data class IdsPair(val userId: UUID, val connectionId: UUID)

fun WorldHostServer.startWsServer() {
    logger.info("Starting WS server on port {}", config.port)
    val server = Server()

    val connector = ServerConnector(server)
    server.addConnector(connector)

    val servletHandler = ServletContextHandler(ServletContextHandler.SESSIONS)
    servletHandler.contextPath = "/"

    val indexUrl = WorldHostServer::class.java.classLoader.getResource("index.html")
    if (indexUrl != null) {
        val servlet = DefaultServlet()
        indexUrl.parentPath.toExternalForm().let {
            logger.info("Using resource base {}", it)
            servletHandler.initParams["org.eclipse.jetty.servlet.Default.resourceBase"] = it
        }
        servletHandler.addServlet(ServletHolder(servlet), "/")
    }

    server.handler = servletHandler

    JakartaWebSocketServletContainerInitializer.configure(servletHandler) { _, wsContainer ->
        wsContainer.addEndpoint(
            ServerEndpointConfig.Builder.create(WorldHostServerEndpoint::class.java, "/")
                .configurator(object : ServerEndpointConfig.Configurator() {
                    val concerningIpHolder = ThreadLocal<String?>()

                    override fun modifyHandshake(
                        sec: ServerEndpointConfig,
                        request: HandshakeRequest,
                        response: HandshakeResponse
                    ) {
                        concerningIpHolder.set(
                            QuotedCSV(false, *request.headers["X-Forwarded-For"]?.toTypedArray() ?: return)
                                .values.lastOrNull()
                        )
                    }

                    override fun <T : Any> getEndpointInstance(endpointClass: Class<T>) =
                        super.getEndpointInstance(endpointClass)
                            .cast<WorldHostServerEndpoint>()
                            .apply { this.server = this@startWsServer }
                            .apply { remoteAddress = concerningIpHolder.get() }
                            .uncheckedCast<T>()
                })
                .build()
        )
    }

    connector.port = config.port
    server.start()
    if (config.baseAddr == null) {
        server.join()
    }
}

@ServerEndpoint(
    "/",
    encoders = [Encoder::class]
)
class WorldHostServerEndpoint {
    class Encoder : jakarta.websocket.Encoder.Binary<WorldHostS2CMessage> {
        override fun encode(obj: WorldHostS2CMessage): ByteBuffer =
            obj.encode(ByteBuffer.allocate(obj.encodedSize())).flip()
    }

    lateinit var session: Session
    lateinit var server: WorldHostServer
    var remoteAddress: String? = null
    lateinit var connection: Connection

    @OnOpen
    fun onOpen(session: Session) {
        this.session = session
        session.maxIdleTimeout = 0
        remoteAddress = remoteAddress
            ?: session.userProperties["jakarta.websocket.endpoint.localAddress"]
                .cast<InetSocketAddress>()
                .hostString
    }

    @OnMessage
    fun onMessage(message: ByteBuffer, session: Session) = with(server) {
        if (!this@WorldHostServerEndpoint::connection.isInitialized) {
            connection = Connection(
                IdsPair(
                    message.uuid,
                    if (message.hasRemaining()) {
                        message.uuid
                    } else {
                        UUID.randomUUID()
                    }
                ),
                remoteAddress!!, this@WorldHostServerEndpoint
            )
            logger.info("Connection opened: {}", connection)
            launchAsync {
                val jsonResponse: JsonObject = httpClient.get("https://api.iplocation.net/") {
                    parameter("ip", connection.address)
                }.body()
                val countryCode = jsonResponse["country_code2"].castOrNull<JsonPrimitive>()?.content
                if (countryCode == null) {
                    logger.warn("No country code returned")
                    return@launchAsync
                }
                if (countryCode !in VALID_COUNTRY_CODES) {
                    logger.warn("Invalid country code {}", countryCode)
                    return@launchAsync
                }
                connection.country = countryCode
            }
            try {
                wsConnections.add(connection)
            } catch (e: IllegalStateException) {
                logger.warn(e.localizedMessage, e)
                session.close(CloseReason(CloseReason.CloseCodes.PROTOCOL_ERROR, "That connection ID is already taken!"))
                return
            }
            logger.info("There are {} open connections.", wsConnections.size)
            session.asyncRemote.sendObject(WorldHostS2CMessage.ConnectionInfo(
                connection.id, config.baseAddr ?: "", config.exJavaPort
            ))
            return
        }
        with(WorldHostC2SMessage.decode(message)) {
            logger.debug("Received message {}", this)
            handle()
        }
    }

    @OnError
    fun onError(t: Throwable) {
        logger.error("An error occurred in WS client handling", t)
    }

    @OnClose
    fun onClose() = with(server) {
        connection.open = false
        logger.info("Connection closed: {}", connection)
        wsConnections.remove(connection)
        logger.info("There are {} open connections.", wsConnections.size)
    }
}
