package io.github.gaming32.worldhostserver

@Suppress("NOTHING_TO_INLINE")
inline fun WebsocketConverterNotFoundException() =
    io.ktor.serialization.WebsocketConverterNotFoundException("No converter was found for websocket")
