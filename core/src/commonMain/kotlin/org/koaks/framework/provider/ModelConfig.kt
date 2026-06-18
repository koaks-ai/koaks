package org.koaks.framework.provider

/**
 * Connection-level configuration for a provider endpoint. Owned by a `ChatModel`
 * and passed to the [org.koaks.framework.transport.Transport] on each request.
 *
 * Generation/sampling parameters are intentionally absent: those bind to the model
 * and are held by each provider's own native config (see `ChatModel.toWire`).
 *
 * @property streamFormat how the response stream is framed on the wire.
 * @property retry connection-level retry budget (see [RetryBudget]).
 */
data class ModelConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val modelName: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val streamFormat: StreamFormat = StreamFormat.SSE,
    val streamEndMarker: String = "[DONE]",
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 600_000,
    val socketTimeoutMs: Long = 600_000,
    val retry: RetryBudget = RetryBudget(),
    val rateLimit: RateLimit? = null,
)

/**
 * How a provider frames its streaming response.
 *  - [SSE]: Server-Sent Events; each event is a `data:` line. (OpenAI/Qwen)
 *  - [NDJSON]: newline-delimited JSON; one JSON object per line. (Ollama)
 */
enum class StreamFormat { SSE, NDJSON }

/**
 * Connection-level retry budget. This only covers transparent, pre-first-byte
 * retries (connection/DNS/5xx/first-packet timeout) and is strictly
 * separate from the loop's session-level `Recovery.Retry`, so the two never multiply.
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
