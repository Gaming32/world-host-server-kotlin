package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default

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

    startWsServer(config)
}
