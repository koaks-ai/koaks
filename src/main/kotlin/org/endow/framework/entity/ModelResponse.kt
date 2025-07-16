package org.endow.framework.entity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.emptyFlow
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.require

class ModelResponse<T> private constructor(
    private val _stream: Flow<T>?,
    private val _response: T?
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        fun <T> fromStream(stream: Flow<T>) = ModelResponse(stream, null)
        fun <T> fromResponse(response: T) = ModelResponse(null, response)
        fun <T> empty(response: T): ModelResponse<T> = ModelResponse(null, response)

        fun <T> fromResult(result: Result<T>, fallback: () -> T): ModelResponse<T> =
            result.fold(
                onSuccess = { fromResponse(it) },
                onFailure = {
                    KotlinLogging.logger {}.error(it) { "Model response failure: ${it.message}" }
                    empty(fallback())
                }
            )
    }

    init {
        require((_stream != null) xor (_response != null)) {
            "ModelResponse must have exactly one non-null value (stream or response)"
        }
    }

    val stream: Flow<T> by LoggedProperty(
        expectedValue = _stream,
        typeName = "stream",
        fallback = { emptyFlow() }
    )

    val response: T by LoggedProperty(
        expectedValue = _response,
        typeName = "response",
        fallback = { throw NoSuchElementException("Response is not available") }
    )

    private inner class LoggedProperty<V>(
        private val expectedValue: V?,
        private val typeName: String,
        private val fallback: () -> V
    ) : ReadOnlyProperty<Any?, V> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): V {
            return expectedValue ?: run {
                logger.warn {
                    "Attempted to access $typeName, but this ModelResponse contains " +
                            if (typeName == "stream") "response" else "stream"
                }
                fallback()
            }
        }
    }
}
