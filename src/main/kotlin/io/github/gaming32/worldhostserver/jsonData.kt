package io.github.gaming32.worldhostserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Country(
    val timezones: List<String>,
    @SerialName("latlng") val latLong: LatitudeLongitude,
    val name: String,
    @SerialName("country_code") val code: String,
    val capital: String? = null
) {
    init {
        require(code.length == 2 && code.all { it.code < 127 && it.isUpperCase() }) {
            "Country.code must be an ISO 3166 Alpha-2 country code"
        }
    }
}

@Serializable
data class ExternalProxy(
    val latLong: LatitudeLongitude,
    val addr: String? = null,
    val port: Int = 9656,
    val baseAddr: String = addr ?: "",
    val mcPort: Int = 25565
)
