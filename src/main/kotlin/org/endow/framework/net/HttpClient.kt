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

/**
 * HTTP 客户端，基于 Spring WebClient 和 Kotlin 协程
 * 提供同步和流式的 HTTP 请求功能
 */
class HttpClient private constructor(
    private val webClient: WebClient,
    private val config: HttpClientConfig
) {

    companion object {
        val logger = KotlinLogging.logger {}

        /**
         * 创建 HttpClient 实例
         */
        fun create(baseUrl: String, apiKey: String): HttpClient {
            return create(HttpClientConfig(baseUrl, apiKey))
        }

        /**
         * 使用配置创建 HttpClient 实例
         */
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

    /**
     * 发送 POST 请求并返回原始 JSON 字符串
     */
    suspend fun postForString(request: DefaultRequest): Result<String> {
        return runCatching {
            val requestBody = JsonUtil.toJson(request)
            logger.debug("Sending POST request: {}", requestBody)

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

    /**
     * 发送 POST 请求并返回解析后的对象
     */
    suspend inline fun <reified T : Any> postForObject(request: DefaultRequest): Result<T> {
        return postForString(request).mapCatching { jsonString ->
            JsonUtil.fromJson<T>(jsonString)
        }
    }

    /**
     * 发送 POST 请求并返回字符串流
     */
    fun postForStringStream(request: DefaultRequest): Flow<String> {
        return try {
            val requestBody = JsonUtil.toJson(request)
            logger.debug { "Sending POST stream request: {$requestBody}" }

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

    /**
     * 发送 POST 请求并返回对象流
     */
    inline fun <reified T : Any> postForObjectStream(request: DefaultRequest): Flow<T> {
        return postForStringStream(request)
            .map { jsonString ->
                try {
                    JsonUtil.fromJson<T>(jsonString)
                } catch (exception: Exception) {
                    logger.error { "Failed to parse JSON: ${jsonString}, ${exception.message}" }
                    throw JsonParseException("Failed to parse JSON response", exception)
                }
            }
    }

    /**
     * 发送 POST 请求并使用回调处理结果
     */
    suspend inline fun <reified T : Any> postWithCallback(
        request: DefaultRequest,
        callback: HttpCallback<T>
    ) {
        try {
            callback.onStart()
            val result = postForObject<T>(request)

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

    /**
     * 发送 POST 流请求并使用回调处理结果
     */
    suspend inline fun <reified T : Any> postStreamWithCallback(
        request: DefaultRequest,
        callback: HttpStreamCallback<T>
    ) {
        try {
            callback.onStart()

            postForObjectStream<T>(request)
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

    /**
     * 发送 GET 请求
     */
    suspend fun get(path: String = ""): Result<String> {
        return runCatching {
            logger.debug("Sending GET request to: {}", path)

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
            logger.error("GET request failed", exception)
            throw mapToHttpClientException(exception)
        }
    }

    /**
     * 发送 GET 请求并返回解析后的对象
     */
    suspend inline fun <reified T : Any> getForObject(path: String = ""): Result<T> {
        return get(path).mapCatching { jsonString ->
            JsonUtil.fromJson<T>(jsonString)
        }
    }

    /**
     * 将异常映射为 HttpClientException
     */
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
                HttpClientException("Request failed: ${exception.message}", exception)
            }
        }
    }
}

/**
 * HTTP 客户端配置
 */
data class HttpClientConfig(
    val baseUrl: String,
    val apiKey: String,
    val timeoutSeconds: Long = 30,
    val maxInMemorySize: Int = 256 * 1024,
    val streamEndMarker: String = "[DONE]"
)

/**
 * HTTP 客户端异常
 */
class HttpClientException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * JSON 解析异常
 */
class JsonParseException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


