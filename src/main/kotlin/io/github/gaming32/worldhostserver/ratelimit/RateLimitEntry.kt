package io.github.gaming32.worldhostserver.ratelimit

data class RateLimitEntry(
    val timeMillis: Long,
    val count: Int
)
