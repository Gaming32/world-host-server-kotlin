package io.github.gaming32.worldhostserver

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    val parser = ArgParser("world-host-server")

    val port by parser.option(ArgType.Int, shortName = "p", description = "Port to bind to").default(9646)
    val baseAddr by parser.option(ArgType.String, shortName = "a", description = "Base address to use for proxy connections")
    val inJavaPort by parser.option(ArgType.Int, shortName = "j", description = "Port to use for Java Edition proxy connections").default(25565)
    val exJavaPort by parser.option(ArgType.Int, shortName = "J", description = "External port to use for Java Edition proxy connections")
    val analyticsTime by parser.option(DurationArgType, description = "Amount of time between analytics syncs").default(10.minutes)

    parser.parse(args)

    WorldHostServer(WorldHostServer.Config(
        port,
        baseAddr,
        inJavaPort,
        exJavaPort ?: inJavaPort,
        analyticsTime
    )).start()
}
