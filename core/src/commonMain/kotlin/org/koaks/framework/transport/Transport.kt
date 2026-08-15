package org.koaks.framework.transport

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.provider.RateLimit
import org.koaks.framework.provider.RetryBudget

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

enum class Framing { Sse, Json, Ndjson }

data class Timeouts(
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 600_000,
    val socketTimeoutMs: Long = 600_000,
    val streamIdleTimeoutMs: Long = 60_000,
)

data class WireCall(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val expect: Framing,
    val idempotencyKey: String? = null,
    val timeouts: Timeouts = Timeouts(),
    val retry: RetryBudget = RetryBudget(),
    val rateLimit: RateLimit? = null,
)

sealed interface WireFrame {
    data class Sse(
        val event: String?,
        val data: String,
        val id: String?,
    ) : WireFrame

    data class Body(
        val contentType: String?,
        val text: String,
    ) : WireFrame

    data class Ndjson(val line: String) : WireFrame

    data class HttpError(
        val status: Int,
        val contentType: String?,
        val body: String,
    ) : WireFrame
}

/**
 * Byte/text framing only. Does not parse provider JSON, drop SSE event names,
 * or interpret `[DONE]`. Provider decoders decide semantic completion.
 */
interface ModelTransport : AutoCloseable {
    fun call(call: WireCall): Flow<WireFrame>
}

@Deprecated("Use ModelTransport", ReplaceWith("ModelTransport"))
typealias Transport = ModelTransport
