package org.koaks.framework.net

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
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

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class HttpClient actual constructor(
    private val config: HttpClientConfig
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeout, TimeUnit.SECONDS)
        .readTimeout(config.readTimeout, TimeUnit.SECONDS)
        .writeTimeout(config.writeTimeout, TimeUnit.SECONDS)
        .callTimeout(config.callTimeout, TimeUnit.SECONDS)
        .build()

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private fun buildRequest(
        url: String,
        block: Request.Builder.() -> Unit
    ): Request {
        return Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .apply(block)
            .build()
    }

    actual suspend fun postAsString(request: InnerChatRequest): Result<String> {
        return runCatching {
            val requestBody = JsonUtil.toJson(request)
            val httpRequest = buildRequest(config.baseUrl) {
                post(requestBody.toRequestBody("application/json".toMediaType()))
            }
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(httpRequest).execute().use { response ->
                    handleResponse(response)
                }
            }
        }.recoverCatching { exception ->
            throw mapToHttpClientException(exception)
        }
    }

    actual suspend fun <T> postAsObject(request: InnerChatRequest, deserializer: KSerializer<T>): Result<T> {
        return postAsString(request).mapCatching { jsonString ->
            JsonUtil.fromJson(jsonString, deserializer)
        }
    }

    actual fun postAsStringStream(request: InnerChatRequest): Flow<String> {
        return flow {
            val requestBody = JsonUtil.toJson(request)
            val httpRequest = buildRequest(config.baseUrl) {
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
            throw mapToHttpClientException(exception)
        }
    }

    actual fun <T> postAsObjectStream(request: InnerChatRequest, deserializer: KSerializer<T>): Flow<T> {
        return postAsStringStream(request).map { jsonString ->
            try {
                JsonUtil.fromJson(jsonString, deserializer)
            } catch (e: Exception) {
                throw JsonParseException("failed to parse json response", e)
            }
        }
    }

    actual suspend fun get(path: String): Result<String> {
        return runCatching {
            val url = if (path.startsWith("http")) path else "${config.baseUrl}$path"
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
            throw mapToHttpClientException(exception)
        }
    }

    actual suspend fun <T> getAsObject(path: String, deserializer: KSerializer<T>): Result<T> {
        return get(path).mapCatching { jsonString ->
            JsonUtil.fromJson(jsonString, deserializer)
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
            is JsonParseException -> HttpClientException("response parsing failed.", exception)
            is SocketTimeoutException -> HttpClientException("socket timeout.", exception)
            is TimeoutException -> HttpClientException(
                "request exceeded callTimeout=${config.callTimeout}s.",
                exception
            )

            is IOException -> HttpClientException("network error.", exception)
            else -> HttpClientException("unexpected error: ${exception.message}", exception)
        }
    }
}