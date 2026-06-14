package org.koaks.framework.transport

import org.koaks.framework.model.GenerationParams

/**
 * Connection-level configuration for a provider endpoint. Owned by a `ChatModel`
 * and passed to the [Transport] on each request.
 *
 * @property streamFormat how the response stream is framed on the wire.
 * @property retry connection-level retry budget (see [RetryBudget]).
 */
data class ModelConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val modelName: String,
    val defaultParams: GenerationParams = GenerationParams(),
    val customHeaders: Map<String, String> = emptyMap(),
    val streamFormat: StreamFormat = StreamFormat.SSE,
    val streamEndMarker: String = "[DONE]",
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 600_000,
    val socketTimeoutMs: Long = 600_000,
    val retry: RetryBudget = RetryBudget(),
)

/**
 * How a provider frames its streaming response.
 *  - [SSE]: Server-Sent Events; each event is a `data:` line. (OpenAI/Qwen)
 *  - [NDJSON]: newline-delimited JSON; one JSON object per line. (Ollama)
 */
enum class StreamFormat { SSE, NDJSON }

/**
 * Connection-level (L0) retry budget. Per design §3.4 this only covers transparent,
 * pre-first-byte retries (connection/DNS/5xx/first-packet timeout) and is strictly
 * separate from the loop's session-level `Recovery.Retry`, so the two never multiply.
 */
data class RetryBudget(
    val maxRetries: Int = 2,
    val initialBackoffMs: Long = 200,
)
