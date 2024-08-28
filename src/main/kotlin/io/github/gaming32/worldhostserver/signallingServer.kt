package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.serialization.uuid
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.io.use

private val logger = KotlinLogging.logger {}

suspend fun WorldHostServer.runSignallingServer() = coroutineScope {
    logger.info { "Starting signalling server on port ${config.port}" }
    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(InetSocketAddress("0.0.0.0", config.port)).use { serverSocket ->
        logger.info { "Started signalling server on ${serverSocket.localAddress}" }
        launch {
            while (true) {
                delay(1000L)
                cleanupExpiredPunchRequests()
            }
        }
        while (true) {
            val signal = serverSocket.receive()
            launch {
                try {
                    val lookupId = ByteBuffer.wrap(signal.packet.readBytes(16)).uuid
                    val address = signal.address.toJavaAddress()
                    val request = portLookups.withLock { remove(lookupId) } ?: return@launch
                    whConnections.byId(request.sourceClient)
                        ?.sendMessage(WorldHostS2CMessage.PortLookupSuccess(lookupId, address.address, address.port))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to handle signal packet from ${signal.address}" }
                }
            }
        }
    }
}

private suspend fun WorldHostServer.cleanupExpiredPunchRequests() {
    val time = System.currentTimeMillis() / 1000L
    val toRemove = portLookupsByExpiryAtSecond.withLock { remove(time) } ?: return
    for (expired in toRemove) {
        val removed = portLookups.withLock { remove(expired) } ?: continue
        whConnections.byId(removed.sourceClient)
            ?.sendMessage(WorldHostS2CMessage.CancelPortLookup(expired))
    }
}
