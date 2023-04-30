package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

class WorldHostServer(val config: Config) {
    data class Config(
        val port: Int,
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

    val proxyConnectionsLock = Mutex()
    val proxyConnections = mutableMapOf<Long, Pair<ConnectionId, ByteWriteChannel>>()

    suspend fun run() = coroutineScope {
        logger.info("Starting world-host-server $SERVER_VERSION with {}", config)
        launch { runAnalytics() }
        if (config.baseAddr == null) {
            startMainServer()
        } else {
            launch { startMainServer() }
            startProxyServer()
        }
    }
}
