package io.github.gaming32.worldhostserver.ratelimit

import io.github.gaming32.worldhostserver.util.LockedObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RateLimitBucket<K : Any>(val name: String, val maxCount: Int, val expiry: Duration) {
    val entries = LockedObject(mutableMapOf<K, RateLimitEntry>())

    @Throws(RateLimited::class)
    suspend fun ratelimit(key: K) = entries.withLock {
        val entry = this[key]
        val currentTime = System.currentTimeMillis()
        if (entry == null) {
            this[key] = RateLimitEntry(currentTime, 1)
            return@withLock
        }
        if (entry.count < maxCount) {
            this[key] = entry.copy(
                timeMillis = currentTime,
                count = entry.count + 1
            )
            return@withLock
        }
        val chop = ((currentTime - entry.timeMillis).milliseconds / expiry).toInt()
        if (chop > 0) {
            val newCount = entry.count - chop
            if (newCount <= 0) {
                remove(key)
            } else {
                this[key] = entry.copy(
                    timeMillis = entry.timeMillis + (expiry * chop).inWholeMilliseconds,
                    count = newCount
                )
            }
            if (newCount < maxCount) {
                return@withLock
            }
        }
        throw RateLimited(this@RateLimitBucket, (entry.timeMillis - currentTime).milliseconds + expiry)
    }
}
