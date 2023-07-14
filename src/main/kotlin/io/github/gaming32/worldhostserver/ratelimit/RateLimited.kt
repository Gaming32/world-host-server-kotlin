package io.github.gaming32.worldhostserver.ratelimit

import kotlin.time.Duration

class RateLimited(
    val bucket: RateLimitBucket<*>, val remaining: Duration
) : Exception("Exceeded the bucket ${bucket.name}. Try again in $remaining.")
