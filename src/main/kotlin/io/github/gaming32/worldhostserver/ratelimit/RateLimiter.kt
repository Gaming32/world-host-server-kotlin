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
                val it = iterator()
                while (it.hasNext()) {
                    if (++i % maxChecksPerYield == 0) {
                        yield()
                    }
                    val entry = it.next()
                    val chop = (
                        (System.currentTimeMillis() - entry.value.timeMillis).milliseconds / bucket.expiry
                    ).toInt()
                    if (chop > 0) {
                        val newCount = entry.value.count - chop
                        if (newCount <= 0) {
                            it.remove()
                        } else {
                            entry.setValue(entry.value.copy(
                                timeMillis = entry.value.timeMillis + (bucket.expiry * chop).inWholeMilliseconds,
                                count = newCount
                            ))
                        }
                    }
                }
            }
        }
    }

    suspend fun ratelimit(key: K) = buckets.forEach { it.ratelimit(key) }
}
