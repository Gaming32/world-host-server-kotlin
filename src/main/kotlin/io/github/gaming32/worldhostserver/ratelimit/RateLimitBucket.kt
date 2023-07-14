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
        if (entry == null || (currentTime - entry.timeMillis).milliseconds >= expiry) {
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
        throw RateLimited(this@RateLimitBucket, (entry.timeMillis - currentTime).milliseconds + expiry)
    }

    override fun toString(): String {
        return "RateLimitBucket(name=$name, maxCount=$maxCount, expiry=$expiry)"
    }
}
