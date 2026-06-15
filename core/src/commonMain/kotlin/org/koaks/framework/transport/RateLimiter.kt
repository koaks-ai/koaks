package org.koaks.framework.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A coroutine-friendly token-bucket rate limiter, shared across all requests made
 * through one [KtorTransport]. [acquire] suspends until a permit is available.
 *
 * Uses a monotonic [TimeSource] (commonMain-safe; no wall clock) and refills
 * [RateLimit.permitsPerInterval] permits each [RateLimit.intervalMs].
 */
internal class RateLimiter(private val limit: RateLimit) {

    private val mutex = Mutex()
    private val mark = TimeSource.Monotonic.markNow()
    private var tokens: Double = limit.permitsPerInterval.toDouble()
    private var lastRefillMs: Long = mark.elapsedNow().inWholeMilliseconds
    private val refillPerMs: Double = limit.permitsPerInterval.toDouble() / limit.intervalMs

    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    // ms until the next whole token is available.
                    ((1.0 - tokens) / refillPerMs).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs.milliseconds)
        }
    }

    private fun refill() {
        val nowMs = mark.elapsedNow().inWholeMilliseconds
        val elapsed = nowMs - lastRefillMs
        if (elapsed > 0) {
            tokens = (tokens + elapsed * refillPerMs).coerceAtMost(limit.permitsPerInterval.toDouble())
            lastRefillMs = nowMs
        }
    }
}
