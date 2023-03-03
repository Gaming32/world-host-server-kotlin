package io.github.gaming32.worldhostserver

@Suppress("NOTHING_TO_INLINE")
inline fun WebsocketConverterNotFoundException() =
    io.ktor.serialization.WebsocketConverterNotFoundException("No converter was found for websocket")

inline fun <reified T> Any?.cast() = this as T

inline fun <reified T> Any?.castOrNull() = this as? T
