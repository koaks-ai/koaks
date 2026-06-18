package org.koaks.framework.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.net.provideEngine
import org.koaks.framework.provider.WireAdapter
import org.koaks.framework.utils.json.JsonUtil
import kotlin.time.Duration.Companion.milliseconds

/**
 * Default [Transport] backed by a single shared Ktor [HttpClient] (connection pool).
 *
 * Streaming line-reader supports both [StreamFormat.SSE] (`data:` lines) and
 * [StreamFormat.NDJSON] (raw JSON per line). Connection-level retry is transparent
 * and ONLY applies before the first chunk has been emitted downstream: once any
 * byte has been forwarded, no retry happens here — that becomes the loop's call.
 */
class KtorTransport(
    private val engineClient: HttpClient = HttpClient(provideEngine()) {
        install(HttpTimeout)
    },
) : Transport {

    private val logger = KotlinLogging.logger {}

    // One limiter per distinct RateLimit config, shared across requests on this transport.
    private val limiters = mutableMapOf<RateLimit, RateLimiter>()
    private val limitersLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun limiterFor(limit: RateLimit): RateLimiter =
        limitersLock.withLock { limiters.getOrPut(limit) { RateLimiter(limit) } }

    override fun <TReq, TResp> stream(
        config: ModelConfig,
        req: TReq,
        adapter: WireAdapter<TReq, TResp>,
    ): Flow<TResp> = flow {
        val body = JsonUtil.toJson(req, adapter.requestSerializer)
        var attempt = 0
        while (true) {
            config.rateLimit?.let { limiterFor(it).acquire() }
            var emittedAny = false
            try {
                openStream(config, body) { line ->
                    val parsed = parseLine(config, line) ?: return@openStream
                    val chunk = JsonUtil.fromJson(parsed, adapter.responseSerializer)
                    emittedAny = true
                    emit(chunk)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Only connection-level (pre-first-byte) failures are retriable here.
                if (!emittedAny && attempt < config.retry.maxRetries) {
                    val backoff = config.retry.initialBackoffMs * (1L shl attempt)
                    logger.warn { "transport retry ${attempt + 1}/${config.retry.maxRetries} after ${backoff}ms: ${e.message}" }
                    attempt++
                    delay(backoff.milliseconds)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Opens the POST stream and invokes [onLine] for each raw text line. The
     * `[DONE]` end marker (SSE) terminates reading. Throws on non-2xx so the retry
     * loop can decide.
     */
    private suspend inline fun openStream(
        config: ModelConfig,
        body: String,
        crossinline onLine: suspend (String) -> Unit,
    ) {
        val stmt = engineClient.preparePost(config.baseUrl) {
            contentType(ContentType.Application.Json)
            if (config.streamFormat == StreamFormat.SSE) {
                accept(ContentType.parse("text/event-stream"))
            } else {
                accept(ContentType.Application.Json)
            }
            if (!config.apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
            for ((k, v) in config.customHeaders) header(k, v)
            setBody(body)
            timeout {
                requestTimeoutMillis = config.requestTimeoutMs
                connectTimeoutMillis = config.connectTimeoutMs
                socketTimeoutMillis = config.socketTimeoutMs
            }
        }

        stmt.execute { response ->
            if (!response.status.isSuccess()) {
                val err = response.bodyAsText()
                throw TransportException("HTTP ${response.status.value}: $err")
            }
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (isEndMarker(config, line)) break
                onLine(line)
            }
        }
    }

    /** Returns the JSON payload for a line, or null if the line should be skipped. */
    private fun parseLine(config: ModelConfig, line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return when (config.streamFormat) {
            StreamFormat.SSE -> {
                if (!trimmed.startsWith("data:")) return null
                val content = trimmed.removePrefix("data:").trim()
                if (content == config.streamEndMarker) null else content
            }

            StreamFormat.NDJSON -> trimmed
        }
    }

    private fun isEndMarker(config: ModelConfig, line: String): Boolean {
        if (config.streamFormat != StreamFormat.SSE) return false
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return false
        return trimmed.removePrefix("data:").trim() == config.streamEndMarker
    }

    override fun close() {
        engineClient.close()
    }
}

/** Transport-level failure (non-2xx, connection). */
class TransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
