package io.github.gaming32.worldhostserver

import io.github.gaming32.worldhostserver.util.DurationArgType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val parser = ArgParser("world-host-server")

    val port by parser.option(
        ArgType.Int, shortName = "p",
        description = "Port to bind to"
    ).default(9646)
    var baseAddr by parser.option(
        ArgType.String, shortName = "a",
        description = "Base address to use for proxy connections"
    )
    val inJavaPort by parser.option(
        ArgType.Int, shortName = "j",
        description = "Port to use for Java Edition proxy connections"
    ).default(25565)
    val exJavaPort by parser.option(
        ArgType.Int, shortName = "J",
        description = "External port to use for Java Edition proxy connections"
    )
    val analyticsTime by parser.option(
        DurationArgType,
        description = "Amount of time between analytics syncs"
    ).default(0.minutes)
    val shutdownTime by parser.option(
        DurationArgType,
        description = "The amount of time before the server automatically shuts down. Useful for restart scripts."
    )

    parser.parse(args)

    if (EXTERNAL_SERVERS != null) {
        if (EXTERNAL_SERVERS.count { it.addr == null } > 1) {
            logger.error { "external_proxies.json defines must have no more than one missing addr field." }
            exitProcess(1)
        }
        for (server in EXTERNAL_SERVERS) {
            if (server.addr == null && server.baseAddr.isNotEmpty()) {
                if (baseAddr == null) {
                    baseAddr = server.baseAddr
                } else {
                    logger.info { "Both the CLI and external_proxies.json specify baseAddr for the local server." }
                    logger.info { "--baseAddr from the CLI will override the value in external_proxies.json." }
                }
                break
            }
        }
    }

    runBlocking {
        @Suppress("NAME_SHADOWING")
        val shutdownTime = shutdownTime
        if (shutdownTime != null) {
            launch {
                logger.info { "Automatically shutting down after $shutdownTime" }
                delay(shutdownTime)
                logger.info { "Shutting down because shutdownTime ($shutdownTime) was reached" }
                exitProcess(0)
            }
        }
        WorldHostServer(WorldHostServer.Config(
            port,
            baseAddr,
            inJavaPort,
            exJavaPort ?: inJavaPort,
            analyticsTime
        )).run()
    }
}
