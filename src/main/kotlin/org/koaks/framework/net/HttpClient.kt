package org.koaks.framework.net

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.utils.JsonUtil
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HttpClient private constructor(
    private val okHttpClient: OkHttpClient,
    private val config: HttpClientConfig
) {

    companion object {
        val logger = KotlinLogging.logger {}

        fun create(baseUrl: String, apiKey: String): HttpClient {
            return create(HttpClientConfig(baseUrl, apiKey))
        }

        fun create(config: HttpClientConfig): HttpClient {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout, TimeUnit.SECONDS)
                .readTimeout(config.readTimeout, TimeUnit.SECONDS)
                .writeTimeout(config.writeTimeout, TimeUnit.SECONDS)
                .callTimeout(config.callTimeout, TimeUnit.SECONDS)
                .build()

            return HttpClient(okHttpClient, config)
        }
    }

    private fun buildRequest(
        url: String,
        block: Request.Builder.() -> Unit
    ): Request {
        return Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .apply(block)
            .build()
    }

    suspend fun postAsString(request: InnerChatRequest): Result<String> {
        return runCatching {
            val requestBody = JsonUtil.toJson(request)
            logger.debug { "Sending POST request: $requestBody" }

            val httpRequest = buildRequest(config.baseUrl) {
                addHeader("Content-Type", "application/json")
                post(requestBody.toRequestBody("application/json".toMediaType()))
            }

            withContext(Dispatchers.IO) {
                okHttpClient.newCall(httpRequest).execute().use { response ->
                    handleResponse(response)
                }
            }
        }.recoverCatching { exception ->
            logger.error { "POST request failed: ${exception.message}" }
            throw mapToHttpClientException(exception)
        }
    }

    suspend inline fun <reified T> postAsObject(request: InnerChatRequest): Result<T> {
        return postAsString(request).mapCatching { jsonString ->
            JsonUtil.fromJson<T>(jsonString)
        }
    }

    fun postAsStringStream(request: InnerChatRequest): Flow<String> {
        return flow {
            val requestBody = JsonUtil.toJson(request)
            logger.debug { "sending POST stream request: $requestBody" }

            val httpRequest = buildRequest(config.baseUrl) {
                addHeader("Content-Type", "application/json")
                post(requestBody.toRequestBody("application/json".toMediaType()))
            }

            okHttpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpClientException("HTTP ${response.code}: ${response.body.string()}")
                }
                response.body.use { body ->
                    val source: BufferedSource = body.source()
                    while (!source.exhausted()) {
                        val rawLine = source.readUtf8Line()?.trim()
                        if (rawLine.isNullOrEmpty() || !rawLine.startsWith("data:")) continue

                        val content = rawLine.removePrefix("data:").trim()
                        if (content == config.streamEndMarker) break
                        emit(content)
                    }
                }
            }
        }.catch { exception ->
            logger.error { "POST stream request failed: ${exception.message}" }
            throw mapToHttpClientException(exception)
        }
    }

    inline fun <reified T> postAsObjectStream(request: InnerChatRequest): Flow<T> {
        return postAsStringStream(request)
            .map { jsonString ->
                try {
                    JsonUtil.fromJson<T>(jsonString)
                } catch (exception: Exception) {
                    logger.error { "failed to parse json: $jsonString, $exception" }
                    throw JsonParseException("failed to parse json response", exception)
                }
            }
    }

    suspend inline fun <reified T> postWithCallback(
        request: InnerChatRequest,
        callback: HttpCallback<T>
    ) {
        try {
            callback.onStart()
            val result = postAsObject<T>(request)

            result.fold(
                onSuccess = { data -> callback.onSuccess(data) },
                onFailure = { exception -> callback.onError(exception) }
            )
        } catch (exception: Exception) {
            callback.onError(exception)
        } finally {
            callback.onComplete()
        }
    }

    suspend inline fun <reified T> postStreamWithCallback(
        request: InnerChatRequest,
        callback: HttpStreamCallback<T>
    ) {
        try {
            callback.onStart()

            postAsObjectStream<T>(request)
                .catch { exception ->
                    callback.onError(exception)
                }
                .onCompletion { exception ->
                    if (exception == null) {
                        callback.onComplete()
                    }
                }
                .collect { item ->
                    callback.onNext(item)
                }
        } catch (exception: Exception) {
            callback.onError(exception)
            callback.onComplete()
        }
    }

    suspend fun get(path: String): Result<String> {
        return runCatching {
            val url = if (path.startsWith("http")) path else "${config.baseUrl}$path"
            logger.debug { "sending GET request to: $url" }

            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            withContext(Dispatchers.IO) {
                okHttpClient.newCall(httpRequest).execute().use { response ->
                    handleResponse(response)
                }
            }
        }.recoverCatching { exception ->
            logger.error { "GET request failed, ${exception.message}" }
            throw mapToHttpClientException(exception)
        }
    }

    suspend inline fun <reified T> getAsObject(path: String): Result<T> {
        return get(path).mapCatching { jsonString ->
            JsonUtil.fromJson<T>(jsonString)
        }
    }

    private fun handleResponse(response: Response): String {
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw HttpClientException("HTTP ${response.code}: $bodyStr")
        }
        return bodyStr
    }

    private fun mapToHttpClientException(exception: Throwable): HttpClientException {
        return when (exception) {
            is HttpClientException -> exception

            is JsonParseException -> {
                HttpClientException("response parsing failed, please check server response format.", exception)
                    .also { logger.debug(exception) { "JSON parse error details" } }
            }

            is SocketTimeoutException -> {
                // maybe connectTimeout / readTimeout / writeTimeout
                HttpClientException("socket timeout (connect/read/write).", exception)
                    .also { logger.debug(exception) { "socket timeout details" } }
            }

            is TimeoutException -> {
                // only callTimeout
                HttpClientException("request exceeded total callTimeout=${config.callTimeout}s.", exception)
                    .also { logger.debug(exception) { "call timeout details" } }
            }

            is IOException -> {
                HttpClientException("network error, please check your connection.", exception)
                    .also { logger.debug(exception) { "I/O error details" } }
            }

            else -> {
                HttpClientException("unexpected error: ${exception.message}", exception)
                    .also { logger.debug(exception) { "unexpected error details" } }
            }
        }
    }

}
