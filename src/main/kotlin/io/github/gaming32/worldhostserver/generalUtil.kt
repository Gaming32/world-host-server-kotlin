package io.github.gaming32.worldhostserver

import kotlinx.cli.ArgType
import kotlinx.cli.ParsingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL
import java.util.*
import kotlin.time.Duration

val VALID_COUNTRY_CODES = Locale.getISOCountries().toSet() + "-"

object DurationArgType : ArgType<Duration>(true) {
    override val description get() = "{ Duration }"

    override fun convert(value: kotlin.String, name: kotlin.String) =
        Duration.parseOrNull(value)
            ?: throw ParsingException("Option $name is expected to be time duration. $value is provided.")
}

inline fun <reified T> Any?.cast() = this as T

@Suppress("NOTHING_TO_INLINE", "UNCHECKED_CAST")
inline fun <T> Any?.uncheckedCast() = this as T


inline fun <reified T> Any?.castOrNull() = this as? T

fun launchAsync(block: suspend CoroutineScope.() -> Unit) = CoroutineScope(Dispatchers.Default).launch(block = block)

val URL.parentPath get() = URL(protocol, host, port, file.parentPath)

val String.parentPath get() = if (this == "/" || isEmpty()) this else substringBeforeLast('/').ifEmpty { "/" }
