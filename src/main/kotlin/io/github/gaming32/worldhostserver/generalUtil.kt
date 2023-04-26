package io.github.gaming32.worldhostserver

import kotlinx.cli.ArgType
import kotlinx.cli.ParsingException
import kotlin.time.Duration

object DurationArgType : ArgType<Duration>(true) {
    override val description get() = "{ Duration }"

    override fun convert(value: kotlin.String, name: kotlin.String) =
        Duration.parseOrNull(value)
            ?: throw ParsingException("Option $name is expected to be time duration. $value is provided.")
}

inline fun <reified T> Any?.cast() = this as T

inline fun <reified T> Any?.castOrNull() = this as? T
