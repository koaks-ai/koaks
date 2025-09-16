package org.koaks.framework.entity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlin.require

@Serializable
class ModelResponse<T> private constructor(
    private val _stream: Flow<T>?,
    private val _value: T?
) {

    companion object {

        private val logger = KotlinLogging.logger {}

        fun <T> fromStream(stream: Flow<T>) = ModelResponse(stream, null)
        fun <T> fromValue(value: T) = ModelResponse(null, value)
        fun <T> empty(value: T): ModelResponse<T> = ModelResponse(null, value)

        fun <T> fromResult(result: Result<T>, fallback: () -> T): ModelResponse<T> =
            result.fold(
                onSuccess = { fromValue(it) },
                onFailure = {
                    logger.error(it) { "Model response failure: ${it.message}" }
                    empty(fallback())
                }
            )
    }

    init {
        require((_stream != null) xor (_value != null)) {
            "ModelResponse must have exactly one non-null value (stream or value)"
        }
    }

    fun stream(): Flow<T> = _stream ?: run {
        logger.error { "Attempted to access stream, but this ModelResponse contains value()" }
        emptyFlow()
    }

    fun value(): T = _value ?: run {
        logger.error { "Attempted to access value, but this ModelResponse contains stream()" }
        throw NoSuchElementException("Response is not available")
    }

}
