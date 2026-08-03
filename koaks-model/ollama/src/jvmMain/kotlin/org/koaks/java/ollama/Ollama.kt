package org.koaks.java.ollama

import java.time.Duration
import org.koaks.java.ModelSpec
import org.koaks.provider.ollama.ollama

/** Java builder for Ollama's NDJSON API. */
class Ollama private constructor() {
    class Builder private constructor() {
        private var baseUrl: String? = null
        private var apiKey: String = "ollama"
        private var modelName: String? = null
        private var temperature: Double? = null
        private var topP: Double? = null
        private var maxTokens: Int? = null
        private var stop: List<String>? = null
        private var think: Boolean? = null
        private var streamIdleTimeoutMillis: Long? = null
        private var parallelToolCalls: Boolean? = null
        private var vision: Boolean? = null
        private var jsonMode: Boolean? = null

        fun baseUrl(baseUrl: String): Builder = apply { this.baseUrl = requireText(baseUrl, "baseUrl") }
        fun apiKey(apiKey: String): Builder = apply { this.apiKey = requireText(apiKey, "apiKey") }
        fun modelName(modelName: String): Builder = apply { this.modelName = requireText(modelName, "modelName") }
        fun temperature(value: Double): Builder = apply { temperature = value }
        fun topP(value: Double): Builder = apply { topP = value }
        fun maxTokens(value: Int): Builder = apply { require(value > 0); maxTokens = value }
        fun stop(values: List<String>): Builder = apply { stop = values.toList() }
        fun stop(vararg values: String): Builder = stop(values.toList())
        fun think(enabled: Boolean): Builder = apply { think = enabled }
        fun streamIdleTimeout(timeout: Duration): Builder = apply { streamIdleTimeoutMillis = positiveMillis(timeout) }
        fun parallelToolCalls(enabled: Boolean): Builder = apply { parallelToolCalls = enabled }
        fun vision(enabled: Boolean): Builder = apply { vision = enabled }
        fun jsonMode(enabled: Boolean): Builder = apply { jsonMode = enabled }

        fun build(): ModelSpec {
            val url = requireNotNull(baseUrl) { "baseUrl is required" }
            val model = requireNotNull(modelName) { "modelName is required" }
            return ModelSpec.create { scope ->
                scope.ollama(url, apiKey, model) {
                    temperature = this@Builder.temperature
                    topP = this@Builder.topP
                    maxTokens = this@Builder.maxTokens
                    stop = this@Builder.stop
                    think = this@Builder.think
                    streamIdleTimeoutMillis?.let { streamIdleTimeoutMs = it }
                    capabilities {
                        parallelToolCalls?.let { this.parallelToolCalls = it }
                        vision?.let { this.vision = it }
                        jsonMode?.let { this.jsonMode = it }
                    }
                }
            }
        }

        companion object { internal fun create(): Builder = Builder() }
    }

    companion object { @JvmStatic fun builder(): Builder = Builder.create() }
}

private fun requireText(value: String, name: String): String {
    require(value.isNotBlank()) { "$name must not be blank" }
    return value
}

private fun positiveMillis(duration: Duration): Long {
    require(!duration.isZero && !duration.isNegative) { "duration must be positive" }
    return duration.toMillis()
}
