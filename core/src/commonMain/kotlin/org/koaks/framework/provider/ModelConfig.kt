package org.koaks.framework.provider

/**
 * Connection-level configuration for a provider endpoint. Owned by a `ChatModel`
 * and used when building each [org.koaks.framework.transport.WireCall].
 *
 * Generation/sampling parameters are intentionally absent: those bind to the model
 * and are held by each provider's own native config.
 */
data class ModelConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val modelName: String,
    val auth: AuthScheme = AuthScheme.Bearer,
    val customHeaders: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 600_000,
    val socketTimeoutMs: Long = 600_000,
    val streamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS,
    val retry: RetryBudget = RetryBudget(),
    val rateLimit: RateLimit? = null,
) {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive" }
        require(socketTimeoutMs > 0) { "socketTimeoutMs must be positive" }
        require(streamIdleTimeoutMs > 0) { "streamIdleTimeoutMs must be positive" }
    }
}

const val DEFAULT_STREAM_IDLE_TIMEOUT_MS: Long = 60_000

/**
 * Connection-level retry budget. This only covers transparent, pre-first-frame
 * retries (connection/DNS/5xx/first-packet timeout) and is strictly
 * separate from the loop's session-level `Recovery.Retry`, so the two never multiply.
 *
 * The same [org.koaks.framework.model.ModelRequest.idempotencyKey] is reused across
 * every attempt in this budget.
 */
data class RetryBudget(
    val maxRetries: Int = 2,
    val initialBackoffMs: Long = 200,
)

/**
 * Client-side rate limit applied before each request leaves the transport. A simple
 * token-bucket: up to [permitsPerInterval] requests are allowed per [intervalMs],
 * with excess requests suspending until a permit frees up. Shared across all calls
 * made through one [org.koaks.framework.transport.KtorTransport] instance.
 */
data class RateLimit(
    val permitsPerInterval: Int,
    val intervalMs: Long = 1_000,
)

fun ModelConfig.timeouts() = org.koaks.framework.transport.Timeouts(
    connectTimeoutMs = connectTimeoutMs,
    requestTimeoutMs = requestTimeoutMs,
    socketTimeoutMs = socketTimeoutMs,
    streamIdleTimeoutMs = streamIdleTimeoutMs,
)

fun ModelConfig.authHeaders(): Map<String, String> = buildMap {
    for ((k, v) in auth.headers(apiKey)) put(k, v)
    putAll(customHeaders)
}
