package org.koaks.java.anthropic

import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.koaks.java.ModelSpec
import org.koaks.provider.anthropic.ANTHROPIC_DEFAULT_BASE_URL
import org.koaks.provider.anthropic.ANTHROPIC_DEFAULT_VERSION
import org.koaks.provider.anthropic.anthropic
import tools.jackson.databind.JsonNode

/** Java builder for Anthropic's Messages API. */
class Anthropic private constructor() {
    class Builder private constructor() {
        private var baseUrl: String = ANTHROPIC_DEFAULT_BASE_URL
        private var apiKey: String? = null
        private var modelName: String? = null
        private var maxTokens: Int = 4096
        private var temperature: Double? = null
        private var topP: Double? = null
        private var topK: Int? = null
        private var stopSequences: List<String>? = null
        private var thinking: JsonNode? = null
        private var anthropicVersion: String = ANTHROPIC_DEFAULT_VERSION
        private var streamIdleTimeoutMillis: Long? = null
        private var parallelToolCalls: Boolean? = null
        private var vision: Boolean? = null
        private var jsonMode: Boolean? = null

        fun baseUrl(baseUrl: String): Builder = apply { this.baseUrl = requireText(baseUrl, "baseUrl") }
        fun apiKey(apiKey: String): Builder = apply { this.apiKey = requireText(apiKey, "apiKey") }
        fun modelName(modelName: String): Builder = apply { this.modelName = requireText(modelName, "modelName") }
        fun maxTokens(value: Int): Builder = apply { require(value > 0); maxTokens = value }
        fun temperature(value: Double): Builder = apply { temperature = value }
        fun topP(value: Double): Builder = apply { topP = value }
        fun topK(value: Int): Builder = apply { require(value > 0); topK = value }
        fun stopSequences(values: List<String>): Builder = apply { stopSequences = values.toList() }
        fun stopSequences(vararg values: String): Builder = stopSequences(values.toList())
        fun thinking(config: JsonNode): Builder = apply {
            require(config.isObject) { "thinking config must be a JSON object" }
            thinking = config
        }
        fun anthropicVersion(value: String): Builder = apply { anthropicVersion = requireText(value, "anthropicVersion") }
        fun streamIdleTimeout(timeout: Duration): Builder = apply { streamIdleTimeoutMillis = positiveMillis(timeout) }
        fun parallelToolCalls(enabled: Boolean): Builder = apply { parallelToolCalls = enabled }
        fun vision(enabled: Boolean): Builder = apply { vision = enabled }
        fun jsonMode(enabled: Boolean): Builder = apply { jsonMode = enabled }

        fun build(): ModelSpec {
            val key = requireNotNull(apiKey) { "apiKey is required" }
            val model = requireNotNull(modelName) { "modelName is required" }
            return ModelSpec.create { scope ->
                scope.anthropic(baseUrl, key, model) {
                    maxTokens = this@Builder.maxTokens
                    temperature = this@Builder.temperature
                    topP = this@Builder.topP
                    topK = this@Builder.topK
                    stopSequences = this@Builder.stopSequences
                    thinking = this@Builder.thinking?.let { Json.parseToJsonElement(it.toString()).jsonObject }
                    anthropicVersion = this@Builder.anthropicVersion
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
