package io.github.gaming32.worldhostserver.util

import io.ktor.utils.io.errors.*
import kotlinx.cli.ArgType
import kotlinx.cli.ParsingException
import kotlinx.coroutines.sync.Mutex
import java.net.SocketException
import java.util.UUID
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration

val NIL_UUID = UUID(0L, 0L)

object DurationArgType : ArgType<Duration>(true) {
    override val description get() = "{ Duration }"

    override fun convert(value: kotlin.String, name: kotlin.String) =
        Duration.parseOrNull(value)
            ?: throw ParsingException("Option $name is expected to be time duration. $value is provided.")
}

inline fun <reified T> Any?.cast() = this as T

inline fun <reified T> Any?.castOrNull() = this as? T

@OptIn(ExperimentalContracts::class)
inline fun <T : Any> Mutex.withTryLock(owner: Any? = null, action: () -> T): T? {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }

    return if (tryLock(owner)) {
        try {
            action()
        } finally {
            unlock(owner)
        }
    } else {
        null
    }
}

fun <E> MutableSet<E>.addWithCircleLimit(value: E, limit: Int): E? =
    if (add(value)) {
        if (size > limit) {
            iterator().let {
                val removed = it.next()
                it.remove()
                removed
            }
        } else {
            null
        }
    } else null

val Exception.isSimpleDisconnectException get() =
    (this is IOException && message == "An existing connection was forcibly closed by the remote host") ||
        (this is SocketException && message == "Connection reset")
