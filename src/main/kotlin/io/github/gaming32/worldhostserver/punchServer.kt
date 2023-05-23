package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

suspend fun WorldHostServer.runPunchServer() = coroutineScope {
    if (config.punchPort == 0) {
        logger.info("Punch server disabled by request")
        return@coroutineScope
    }
    logger.info("Starting punch server")
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.punchPort).use { serverSocket ->
        logger.info("Started punch server on ${serverSocket.localAddress}")
        while (true) {
            val punchSocket = serverSocket.accept()
            logger.info("Accepted punch connection from {}", punchSocket.remoteAddress)
            launch {
                // TODO: Implement
            }
        }
    }
}
