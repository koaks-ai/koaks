package org.endow.framework.net

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.endow.framework.entity.DefaultRequest
import org.endow.framework.utils.JsonUtil
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.util.concurrent.TimeoutException


class HttpClient private constructor(
    private val webClient: WebClient,
    private val config: HttpClientConfig
) {

    companion object {
        val logger = KotlinLogging.logger {}

        fun create(baseUrl: String, apiKey: String): HttpClient {
            return create(HttpClientConfig(baseUrl, apiKey))
        }

        fun create(config: HttpClientConfig): HttpClient {
            val webClient = WebClient.builder()
                .baseUrl(config.baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${config.apiKey}")
                .codecs { configurer ->
                    configurer.defaultCodecs().maxInMemorySize(config.maxInMemorySize)
                }
                .build()

            return HttpClient(webClient, config)
        }
    }

    suspend fun postAsString(request: DefaultRequest): Result<String> {
        return runCatching {
            val requestBody = JsonUtil.toJson(request)
            logger.debug { "Sending POST request: $requestBody" }

            webClient.post()
                .bodyValue(requestBody)
                .retrieve()
                .onStatus({ status -> status.isError }) { clientResponse ->
                    clientResponse.bodyToMono(String::class.java)
                        .map { body ->
                            HttpClientException("HTTP ${clientResponse.statusCode()}: $body")
                        }
                }
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .awaitSingle()
        }.recoverCatching { exception ->
            logger.error { "POST request failed ${exception.message}" }
            throw mapToHttpClientException(exception)
        }
    }

    suspend inline fun <reified T> postAsObject(request: DefaultRequest): Result<T> {
        return postAsString(request).mapCatching { jsonString ->
            JsonUtil.fromJson<T>(jsonString)
        }
    }

    fun postAsStringStream(request: DefaultRequest): Flow<String> {
        return try {
            val requestBody = JsonUtil.toJson(request)
            logger.debug { "Sending POST stream request: $requestBody" }

            webClient.post()
                .bodyValue(requestBody)
                .retrieve()
                .onStatus({ status -> status.isError }) { clientResponse ->
                    clientResponse.bodyToMono(String::class.java)
                        .map { body ->
                            HttpClientException("HTTP ${clientResponse.statusCode()}: $body")
                        }
                }
                .bodyToFlux(String::class.java)
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .filter { it.isNotEmpty() && it != config.streamEndMarker }
                .asFlow()
                .catch { exception ->
                    logger.error { "POST stream request failed, ${exception.message}" }
                    throw mapToHttpClientException(exception)
                }
        } catch (exception: Exception) {
            logger.error { "Failed to create POST stream request, ${exception.message}" }
            throw mapToHttpClientException(exception)
        }
    }

    inline fun <reified T> postAsObjectStream(request: DefaultRequest): Flow<T> {
        return postAsStringStream(request)
            .map { jsonString ->
                try {
                    JsonUtil.fromJson<T>(jsonString)
                } catch (exception: Exception) {
                    logger.error { "Failed to parse JSON: $jsonString, $exception" }
                    throw JsonParseException("Failed to parse JSON response", exception)
                }
            }
    }

    suspend inline fun <reified T> postWithCallback(
        request: DefaultRequest,
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
        request: DefaultRequest,
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

    suspend fun get(path: String = ""): Result<String> {
        return runCatching {
            logger.debug { "Sending GET request to: $path" }

            webClient.get()
                .uri(path)
                .retrieve()
                .onStatus({ status -> status.isError }) { clientResponse ->
                    clientResponse.bodyToMono(String::class.java)
                        .map { body ->
                            HttpClientException("HTTP ${clientResponse.statusCode()}: $body")
                        }
                }
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .awaitSingle()
        }.recoverCatching { exception ->
            logger.error { "GET request failed, ${exception.message}" }
            throw mapToHttpClientException(exception)
        }
    }

    suspend inline fun <reified T> getAsObject(path: String = ""): Result<T> {
        return get(path).mapCatching { jsonString ->
            JsonUtil.fromJson<T>(jsonString)
        }
    }

    private fun mapToHttpClientException(exception: Throwable): HttpClientException {
        return when (exception) {
            is HttpClientException -> exception
            is WebClientResponseException -> {
                HttpClientException(
                    "HTTP ${exception.statusCode}: ${exception.responseBodyAsString}",
                    exception
                )
            }

            is TimeoutException -> {
                HttpClientException("Request timeout", exception)
            }

            else -> {
                HttpClientException("Request failed: $exception", exception)
            }
        }
    }
}


data class HttpClientConfig(
    val baseUrl: String,
    val apiKey: String,
    val timeoutSeconds: Long = 60,
    val maxInMemorySize: Int = 256 * 1024,
    val streamEndMarker: String = "[DONE]"
)


class HttpClientException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


class JsonParseException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


