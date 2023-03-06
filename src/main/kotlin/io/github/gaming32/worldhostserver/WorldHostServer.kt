package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex

private val logger = KotlinLogging.logger {}

class WorldHostServer(val config: Config) {
    data class Config(val port: Int, val baseAddr: String?, val inJavaPort: Int, val exJavaPort: Int)

    val wsConnections = ConnectionSetAsync()

    val proxyConnectionsLock = Mutex()
    val proxyConnections = mutableMapOf<Long, ByteWriteChannel>()

    fun start() {
        logger.info("Starting world-host-server with {}", config)
        startWsServer()
        startProxyServer()
    }
}
