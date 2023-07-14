package io.github.gaming32.worldhostserver.ratelimit

import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds

class RateLimiter<K : Any>(vararg buckets: RateLimitBucket<K>) {
    val buckets = buckets.toList()

    @Throws(RateLimited::class)
    suspend fun pumpLimits(maxChecksPerYield: Int) {
        var i = 0
        for (bucket in buckets) {
            bucket.entries.withLock {
                val it = values.iterator()
                while (it.hasNext()) {
                    if (++i % maxChecksPerYield == 0) {
                        yield()
                    }
                    val entry = it.next()
                    if ((System.currentTimeMillis() - entry.timeMillis).milliseconds >= bucket.expiry) {
                        it.remove()
                    }
                }
            }
        }
    }

    suspend fun ratelimit(key: K) {
        var error: RateLimited? = null
        for (bucket in buckets) {
            try {
                bucket.ratelimit(key)
            } catch (e: RateLimited) {
                if (error == null) {
                    error = e
                }
            }
        }
        if (error != null) {
            throw error
        }
    }

    override fun toString(): String {
        return "RateLimiter(buckets=$buckets)"
    }
}
