package org.koaks.framework.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod as KtorMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koaks.framework.net.provideEngine
import org.koaks.framework.provider.RateLimit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Default [ModelTransport] backed by a single shared Ktor [HttpClient].
 *
 * Connection-level retry is transparent and ONLY applies before the first frame
 * has been emitted downstream. The [WireCall.idempotencyKey] is sent on every
 * attempt so a provider that honors `Idempotency-Key` can collapse duplicates.
 */
class KtorTransport(
    private val engineClient: HttpClient = HttpClient(provideEngine()) {
        install(HttpTimeout)
    },
) : ModelTransport {

    private val logger = KotlinLogging.logger {}
    private val limiters = mutableMapOf<RateLimit, RateLimiter>()
    private val limitersLock = Mutex()

    private suspend fun limiterFor(limit: RateLimit): RateLimiter =
        limitersLock.withLock { limiters.getOrPut(limit) { RateLimiter(limit) } }

    override fun call(call: WireCall): Flow<WireFrame> = flow {
        var attempt = 0
        while (true) {
            call.rateLimit?.let { limiterFor(it).acquire() }
            var emittedAny = false
            try {
                val httpError = execute(call) { frame ->
                    emittedAny = true
                    emit(frame)
                }
                if (httpError != null) {
                    if (attempt < call.retry.maxRetries && httpError.status.isRetriableStatus()) {
                        val backoff = call.retry.initialBackoffMs * (1L shl attempt)
                        logger.warn {
                            "transport retry ${attempt + 1}/${call.retry.maxRetries} " +
                                "after ${backoff}ms: HTTP ${httpError.status}"
                        }
                        attempt++
                        delay(backoff.milliseconds)
                        continue
                    }
                    emit(httpError)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!emittedAny && attempt < call.retry.maxRetries && e.isRetriableTransportFailure()) {
                    val backoff = call.retry.initialBackoffMs * (1L shl attempt)
                    logger.warn { "transport retry ${attempt + 1}/${call.retry.maxRetries} after ${backoff}ms: ${e.message}" }
                    attempt++
                    delay(backoff.milliseconds)
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun execute(
        call: WireCall,
        onFrame: suspend (WireFrame) -> Unit,
    ): WireFrame.HttpError? {
        var httpError: WireFrame.HttpError? = null
        val stmt = engineClient.prepareRequest(call.url) {
            method = call.method.toKtor()
            contentType(ContentType.Application.Json)
            when (call.expect) {
                Framing.Sse -> accept(ContentType.parse("text/event-stream"))
                Framing.Json, Framing.Ndjson -> accept(ContentType.Application.Json)
            }
            for ((k, v) in call.headers) header(k, v)
            call.idempotencyKey?.let { header("Idempotency-Key", it) }
            call.body?.let { setBody(it) }
            timeout {
                requestTimeoutMillis = call.timeouts.requestTimeoutMs
                connectTimeoutMillis = call.timeouts.connectTimeoutMs
                socketTimeoutMillis = call.timeouts.socketTimeoutMs
            }
        }

        stmt.execute { response ->
            if (!response.status.isSuccess()) {
                val err = response.bodyAsText()
                httpError = WireFrame.HttpError(
                    status = response.status.value,
                    contentType = response.contentType()?.toString(),
                    body = err,
                )
                return@execute
            }
            when (call.expect) {
                Framing.Json -> onFrame(
                    WireFrame.Body(response.contentType()?.toString(), response.bodyAsText()),
                )
                Framing.Sse -> readSse(
                    response.bodyAsChannel(),
                    call.timeouts.streamIdleTimeoutMs,
                    onFrame,
                )
                Framing.Ndjson -> readNdjson(
                    response.bodyAsChannel(),
                    call.timeouts.streamIdleTimeoutMs,
                    onFrame,
                )
            }
        }
        return httpError
    }

    override fun close() {
        engineClient.close()
    }
}

class TransportException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class StreamIdleTimeoutException(val idleTimeoutMs: Long) : RuntimeException(
    "model response stream was idle for ${idleTimeoutMs}ms",
)

private data class StreamLine(val value: String?)

internal suspend fun readStreamLine(channel: ByteReadChannel, idleTimeoutMs: Long): String? {
    require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
    val result = withTimeoutOrNull(idleTimeoutMs) {
        StreamLine(channel.readUTF8Line())
    } ?: throw StreamIdleTimeoutException(idleTimeoutMs)
    return result.value
}

private suspend fun readNdjson(
    channel: ByteReadChannel,
    idleTimeoutMs: Long,
    onFrame: suspend (WireFrame) -> Unit,
) {
    while (!channel.isClosedForRead) {
        val line = readStreamLine(channel, idleTimeoutMs) ?: break
        if (line.isNotBlank()) onFrame(WireFrame.Ndjson(line))
    }
}

private suspend fun readSse(
    channel: ByteReadChannel,
    idleTimeoutMs: Long,
    onFrame: suspend (WireFrame) -> Unit,
) {
    var event: String? = null
    var id: String? = null
    val data = StringBuilder()
    var hasData = false

    suspend fun flush() {
        if (!hasData && event == null && id == null) return
        onFrame(WireFrame.Sse(event = event, data = data.toString(), id = id))
        event = null
        id = null
        data.clear()
        hasData = false
    }

    while (!channel.isClosedForRead) {
        val line = readStreamLine(channel, idleTimeoutMs) ?: break
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("id:") -> id = line.removePrefix("id:").trim()
            line.startsWith("data:") -> {
                if (hasData) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
                hasData = true
            }
        }
    }
    flush()
}

private fun HttpMethod.toKtor(): KtorMethod = when (this) {
    HttpMethod.GET -> KtorMethod.Get
    HttpMethod.POST -> KtorMethod.Post
    HttpMethod.PUT -> KtorMethod.Put
    HttpMethod.PATCH -> KtorMethod.Patch
    HttpMethod.DELETE -> KtorMethod.Delete
}

private fun Int.isRetriableStatus(): Boolean = this == 408 || this == 429 || this >= 500

private fun Throwable.isRetriableTransportFailure(): Boolean =
    this !is TransportException || statusCode == null || statusCode.isRetriableStatus()
