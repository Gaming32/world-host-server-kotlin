package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging

private val logger = KotlinLogging.logger {}

class WorldHostServer(val config: Config) {
    data class Config(val port: Int, val baseAddr: String?, val javaPort: Int)

    val connections = ConnectionSetAsync()

    fun start() {
        logger.info("Starting world-host-server with {}", config)
        startWsServer()
    }
}
