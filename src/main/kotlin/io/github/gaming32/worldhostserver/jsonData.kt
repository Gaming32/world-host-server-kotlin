package io.github.gaming32.worldhostserver

import kotlinx.serialization.Serializable

@Serializable
data class ExternalProxy(
    val latLong: LatitudeLongitude,
    val addr: String? = null,
    val port: Int = 9656,
    val baseAddr: String = addr ?: "",
    val mcPort: Int = 25565
)
