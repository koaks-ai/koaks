package org.koaks.framework.net

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
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
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import org.koaks.framework.model.TypeAdapter
import org.koaks.framework.utils.JsonUtil

class HttpClient(
    private val config: HttpClientConfig
) {
    private val ktorClient = io.ktor.client.HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = config.callTimeout * 1000
            connectTimeoutMillis = config.connectTimeout * 1000
            socketTimeoutMillis = config.readTimeout * 1000
        }
        defaultRequest {
            url(config.baseUrl)
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        }
    }

    suspend fun <T> postAsString(request: T, serializer: KSerializer<T>): Result<String> {
        return runCatching {
            val response: HttpResponse = ktorClient.post {
                setBody(JsonUtil.toJson(request, serializer))
            }
            response.bodyAsText()
        }.recoverCatching { e ->
            throw mapToHttpClientException(e)
        }
    }

    suspend fun <T, R> postAsObject(request: T, adapter: TypeAdapter<T, R>): Result<R> {
        return postAsString(request, adapter.serializer).mapCatching { jsonString ->
            JsonUtil.fromJson(jsonString, adapter.deserializer)
        }
    }

    fun <T> postAsStringStream(request: T, serializer: KSerializer<T>): Flow<String> = callbackFlow {
        val job = launch(Dispatchers.Default) {
            // use preparePost + execute to ensure the entire read operation is performed within the same lifecycle
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
                    close(HttpClientException("HTTP ${response.status.value}: $err"))
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                    val content = trimmed.removePrefix("data:").trim()
                    if (content == config.streamEndMarker) break
                    trySend(content)
                }
            }
            close()
        }

        awaitClose { job.cancel() }
    }.catch { e ->
        throw mapToHttpClientException(e)
    }

    fun <T, R> postAsObjectStream(request: T, adapter: TypeAdapter<T, R>): Flow<R> = flow {
        postAsStringStream(request, adapter.serializer).collect {
            try {
                emit(JsonUtil.fromJson(it, adapter.deserializer))
            } catch (e: Exception) {
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