package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.util.LockedObject
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

class WorldHostServer(val config: Config) {
    data class Config(
        val port: Int,
        val punchPort: Int,
        val baseAddr: String?,
        val inJavaPort: Int,
        val exJavaPort: Int,
        val analyticsTime: Duration
    )

    val httpClient = HttpClient(Java) {
        install(UserAgent) {
            agent = "WorldHostServer/$SERVER_VERSION"
        }
        install(ContentNegotiation) {
            json()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
        }
    }

    val whConnections = ConnectionSetAsync()

    val proxyConnections = LockedObject(mutableMapOf<Long, Pair<ConnectionId, ByteWriteChannel>>())

    val waitingPunch = LockedObject(mutableMapOf<Pair<ConnectionId, ConnectionId>, PunchClient>())

    val rememberedFriendRequests = LockedObject(mutableMapOf<UUID, MutableSet<UUID>>())

    val receivedFriendRequests = LockedObject(mutableMapOf<UUID, MutableSet<UUID>>())

    suspend fun run() = coroutineScope {
        logger.info { "Starting world-host-server $SERVER_VERSION with $config" }
        EXTERNAL_SERVERS?.forEach { proxy ->
            if (proxy.addr == null) return@forEach
            launch {
                logger.info { "Attempting to ping ${proxy.addr}, ${proxy.port}" }
                try {
                    aSocket(SelectorManager(Dispatchers.IO)).tcp().connect(proxy.addr, proxy.port).close()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to ping ${proxy.addr}, ${proxy.port}" }
                    return@launch
                }
                logger.info { "Successfully pinged ${proxy.addr}, ${proxy.port}" }
            }
        }
        launch { runAnalytics() }
        launch { runProxyServer() }
        launch { runPunchServer() }
        runMainServer()
    }
}
