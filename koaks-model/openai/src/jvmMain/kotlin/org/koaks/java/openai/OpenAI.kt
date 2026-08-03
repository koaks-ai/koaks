package org.koaks.java.openai

import java.time.Duration
import org.koaks.java.ModelSpec
import org.koaks.provider.openai.OPENAI_DEFAULT_BASE_URL
import org.koaks.provider.openai.openai

/** Java builder for OpenAI Chat Completions. */
class OpenAI private constructor() {
    enum class ReasoningEffort(val wireValue: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

    class Builder private constructor() {
        private var baseUrl: String = OPENAI_DEFAULT_BASE_URL
        private var apiKey: String? = null
        private var modelName: String? = null
        private var temperature: Double? = null
        private var maxCompletionTokens: Int? = null
        private var topP: Double? = null
        private var stop: List<String>? = null
        private var presencePenalty: Double? = null
        private var frequencyPenalty: Double? = null
        private var reasoningEffort: ReasoningEffort? = null
        private var streamIdleTimeoutMillis: Long? = null
        private var requireStreamEndMarker: Boolean? = null
        private var parallelToolCalls: Boolean? = null
        private var vision: Boolean? = null
        private var jsonMode: Boolean? = null

        fun baseUrl(baseUrl: String): Builder = apply { this.baseUrl = requireText(baseUrl, "baseUrl") }
        fun apiKey(apiKey: String): Builder = apply { this.apiKey = requireText(apiKey, "apiKey") }
        fun modelName(modelName: String): Builder = apply { this.modelName = requireText(modelName, "modelName") }
        fun temperature(value: Double): Builder = apply { temperature = value }
        fun maxCompletionTokens(value: Int): Builder = apply { require(value > 0); maxCompletionTokens = value }
        fun topP(value: Double): Builder = apply { topP = value }
        fun stop(values: List<String>): Builder = apply { stop = values.toList() }
        fun stop(vararg values: String): Builder = stop(values.toList())
        fun presencePenalty(value: Double): Builder = apply { presencePenalty = value }
        fun frequencyPenalty(value: Double): Builder = apply { frequencyPenalty = value }
        fun reasoningEffort(value: ReasoningEffort): Builder = apply { reasoningEffort = value }
        fun streamIdleTimeout(timeout: Duration): Builder = apply { streamIdleTimeoutMillis = positiveMillis(timeout) }
        fun requireStreamEndMarker(required: Boolean): Builder = apply { requireStreamEndMarker = required }
        fun parallelToolCalls(enabled: Boolean): Builder = apply { parallelToolCalls = enabled }
        fun vision(enabled: Boolean): Builder = apply { vision = enabled }
        fun jsonMode(enabled: Boolean): Builder = apply { jsonMode = enabled }

        fun build(): ModelSpec {
            val key = requireNotNull(apiKey) { "apiKey is required" }
            val model = requireNotNull(modelName) { "modelName is required" }
            return ModelSpec.create { scope ->
                scope.openai(baseUrl, key, model) {
                    temperature = this@Builder.temperature
                    maxCompletionTokens = this@Builder.maxCompletionTokens
                    topP = this@Builder.topP
                    stop = this@Builder.stop
                    presencePenalty = this@Builder.presencePenalty
                    frequencyPenalty = this@Builder.frequencyPenalty
                    reasoningEffort = this@Builder.reasoningEffort?.wireValue
                    streamIdleTimeoutMillis?.let { streamIdleTimeoutMs = it }
                    requireStreamEndMarker?.let { this.requireStreamEndMarker = it }
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
