package org.koaks.framework.net

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import org.koaks.framework.model.TypeAdapter
import org.koaks.framework.utils.json.JsonUtil

class KtorHttpClient(
    private val config: HttpClientConfig
) {

    private val logger = KotlinLogging.logger {}

    private val ktorClient = HttpClient(provideEngine()) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.callTimeout * 1000L
            connectTimeoutMillis = config.connectTimeout * 1000L
            socketTimeoutMillis = config.readTimeout * 1000L
            logger.debug {
                "HttpClient timeout: ${requestTimeoutMillis}ms," +
                        "connectTimeout: ${connectTimeoutMillis}ms," +
                        "readTimeout: ${socketTimeoutMillis}ms. "
            }
        }
        defaultRequest {
            url(config.baseUrl)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        }
    }

    suspend fun <T> postAsString(request: T, serializer: KSerializer<T>): Result<String> {
        return runCatching {
            logger.debug { "postAsString: ${config.baseUrl}" }
            val response: HttpResponse = ktorClient.post {
                setBody(JsonUtil.toJson(request, serializer))
            }
            response.bodyAsText()
        }.recoverCatching { e ->
            logger.error { "postAsString failed: $e" }
            throw mapToHttpClientException(e)
        }
    }

    suspend fun <T, R> postAsObject(request: T, adapter: TypeAdapter<T, R>): Result<R> {
        return postAsString(request, adapter.serializer).mapCatching { jsonString ->
            logger.debug { "postAsObject: ${config.baseUrl}" }
            logger.info { "rawJson: $jsonString" }
            JsonUtil.fromJson(jsonString, adapter.deserializer)
        }
    }

    fun <T> postAsStringStream(request: T, serializer: KSerializer<T>): Flow<String> = callbackFlow {
        val job = launch(Dispatchers.Default) {
            logger.debug { "Sending SSE request: ${JsonUtil.toJson(request, serializer)}" }
            val stmt = ktorClient.preparePost {
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(JsonUtil.toJson(request, serializer))
                timeout {
                    requestTimeoutMillis = null
                    socketTimeoutMillis = null
                }
            }

            stmt.execute { response ->
                if (!response.status.isSuccess()) {
                    val err = response.bodyAsText()
                    logger.error { "HTTP request failed with status ${response.status.value}: $err" }
                    close(HttpClientException("HTTP ${response.status.value}: $err"))
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    logger.debug { "Received line: '$trimmed'" }

                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue

                    val content = trimmed.removePrefix("data:").trim()
                    logger.debug { "Parsed content: '$content'" }

                    if (content == config.streamEndMarker) {
                        logger.debug { "Stream end marker received, closing flow" }
                        close()
                        break
                    }

                    trySend(content).onFailure {
                        logger.warn { "Failed to send content: $it" }
                    }
                }
            }
        }

        awaitClose {
            logger.debug { "Flow cancelled, cancelling job" }
            job.cancel()
        }
    }.catch { e ->
        logger.error(e) { "SSE flow exception" }
        throw mapToHttpClientException(e)
    }

    fun <T, R> postAsObjectStream(request: T, adapter: TypeAdapter<T, R>): Flow<R> = flow {
        postAsStringStream(request, adapter.serializer).collect {
            try {
                emit(JsonUtil.fromJson(it, adapter.deserializer))
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse json response" }
                throw JsonParseException("failed to parse json response", e)
            }
        }
    }

    suspend fun get(path: String): Result<String> {
        return runCatching {
            val url = if (path.startsWith("http")) path else "${config.baseUrl}$path"
            ktorClient.get(url).bodyAsText()
        }.recoverCatching { e ->
            throw mapToHttpClientException(e)
        }
    }

    suspend fun <R> getAsObject(path: String, deserializer: KSerializer<R>): Result<R> {
        return get(path).mapCatching { jsonString ->
            JsonUtil.fromJson(jsonString, deserializer)
        }
    }

    private fun mapToHttpClientException(exception: Throwable): HttpClientException {
        return when (exception) {
            is HttpClientException -> exception
            is JsonParseException -> HttpClientException("response parsing failed.", exception)
            is SocketTimeoutException -> HttpClientException("socket timeout.", exception)
            is TimeoutCancellationException -> HttpClientException(
                "request exceeded callTimeout=${config.callTimeout}s.",
                exception
            )

            is IOException -> HttpClientException("network error.", exception)
            else -> HttpClientException("unexpected error: ${exception.message}", exception)
        }
    }
}