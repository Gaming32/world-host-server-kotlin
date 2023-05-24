package io.github.gaming32.worldhostserver.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LockedObject<T>(
    @PublishedApi internal val obj: T,
    @PublishedApi internal val mutex: Mutex = Mutex()
) {
    val isLocked by mutex::isLocked

    suspend inline fun <R> withLock(owner: Any? = null, action: T.() -> R) = mutex.withLock(owner) { obj.action() }

    inline fun <R : Any> withTryLock(owner: Any? = null, action: T.() -> R) = mutex.withTryLock(owner) { obj.action() }

    fun holdsLock(owner: Any) = mutex.holdsLock(owner)

    override fun toString() = withTryLock { "LockedObject(obj=$this)" } ?: "LockedObject(mutex=$mutex)"
}
