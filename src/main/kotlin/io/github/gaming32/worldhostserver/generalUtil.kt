package io.github.gaming32.worldhostserver

import kotlinx.cli.ArgType
import kotlinx.cli.ParsingException
import java.util.*
import kotlin.time.Duration

val VALID_COUNTRY_CODES = Locale.getISOCountries().toSet() + "-"

object DurationArgType : ArgType<Duration>(true) {
    override val description get() = "{ Duration }"

    override fun convert(value: kotlin.String, name: kotlin.String) =
        Duration.parseOrNull(value)
            ?: throw ParsingException("Option $name is expected to be time duration. $value is provided.")
}

@Suppress("NOTHING_TO_INLINE")
inline fun WebsocketConverterNotFoundException() =
    io.ktor.serialization.WebsocketConverterNotFoundException("No converter was found for websocket")

inline fun <reified T> Any?.cast() = this as T

inline fun <reified T> Any?.castOrNull() = this as? T
