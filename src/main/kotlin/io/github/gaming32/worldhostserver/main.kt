package io.github.gaming32.worldhostserver

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default

const val PROXY_SERVER_PREFIX = "connect0000-"

fun main(args: Array<String>) {
    val parser = ArgParser("world-host-server")

    val port by parser.option(ArgType.Int, shortName = "p", description = "Port to bind to").default(9646)
    val baseAddr by parser.option(ArgType.String, shortName = "b", description = "Base address to use for proxy connections")
    val javaPort by parser.option(ArgType.Int, shortName = "J", description = "Port to use for Java Edition proxy connections").default(25565)

    parser.parse(args)

    WorldHostServer(WorldHostServer.Config(port, baseAddr, javaPort)).start()
}
