package org.koaks.java.openai

import java.time.Duration
import org.koaks.java.ModelSpec
import org.koaks.provider.openai.OPENAI_DEFAULT_BASE_URL
import org.koaks.provider.openai.responses.ResponsesStateMode
import org.koaks.provider.openai.responses.openaiResponses

/** Java builder for OpenAI Responses API. */
class OpenAIResponses private constructor() {
    class Builder private constructor() {
        private var baseUrl: String = OPENAI_DEFAULT_BASE_URL
        private var apiKey: String? = null
        private var modelName: String? = null
        private var temperature: Double? = null
        private var topP: Double? = null
        private var maxOutputTokens: Int? = null
        private var streamIdleTimeoutMillis: Long? = null
        private var stateMode: ResponsesStateMode = ResponsesStateMode.Replayable
        private var persistCheckpoint: Boolean = false
        private var enableWebSearch: Boolean = false
        private var parallelToolCalls: Boolean? = null
        private var vision: Boolean? = null
        private var jsonMode: Boolean? = null
        private var jsonSchema: Boolean? = null

        fun baseUrl(baseUrl: String): Builder = apply { this.baseUrl = requireText(baseUrl, "baseUrl") }
        fun apiKey(apiKey: String): Builder = apply { this.apiKey = requireText(apiKey, "apiKey") }
        fun modelName(modelName: String): Builder = apply { this.modelName = requireText(modelName, "modelName") }
        fun temperature(value: Double): Builder = apply { temperature = value }
        fun topP(value: Double): Builder = apply { topP = value }
        fun maxOutputTokens(value: Int): Builder = apply { require(value > 0); maxOutputTokens = value }
        fun streamIdleTimeout(timeout: Duration): Builder = apply { streamIdleTimeoutMillis = positiveMillis(timeout) }
        fun stateMode(mode: ResponsesStateMode): Builder = apply { stateMode = mode }
        fun persistCheckpoint(enabled: Boolean): Builder = apply { persistCheckpoint = enabled }
        fun webSearch(enabled: Boolean): Builder = apply { enableWebSearch = enabled }
        fun parallelToolCalls(enabled: Boolean): Builder = apply { parallelToolCalls = enabled }
        fun vision(enabled: Boolean): Builder = apply { vision = enabled }
        fun jsonMode(enabled: Boolean): Builder = apply { jsonMode = enabled }
        fun jsonSchema(enabled: Boolean): Builder = apply { jsonSchema = enabled }

        fun build(): ModelSpec {
            val key = requireNotNull(apiKey) { "apiKey is required" }
            val model = requireNotNull(modelName) { "modelName is required" }
            return ModelSpec.create { scope ->
                scope.openaiResponses(baseUrl, key, model) {
                    temperature = this@Builder.temperature
                    topP = this@Builder.topP
                    maxOutputTokens = this@Builder.maxOutputTokens
                    streamIdleTimeoutMillis?.let { streamIdleTimeoutMs = it }
                    stateMode = this@Builder.stateMode
                    persistCheckpoint = this@Builder.persistCheckpoint
                    if (enableWebSearch) webSearch()
                    capabilities {
                        parallelToolCalls.let { this.parallelToolCalls = it }
                        vision.let { this.vision = it }
                        jsonMode.let { this.jsonMode = it }
                        jsonSchema.let { this.jsonSchema = it }
                    }
                }
            }
        }

        companion object { internal fun create(): Builder = Builder() }
    }

    companion object { @JvmStatic fun builder(): Builder = Builder.create() }
}
