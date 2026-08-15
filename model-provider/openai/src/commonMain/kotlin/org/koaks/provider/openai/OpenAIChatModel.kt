package org.koaks.provider.openai

import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ProviderId
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.transport.ModelTransport
import org.koaks.provider.chatcompletions.ChatCompletionsModel
import org.koaks.provider.chatcompletions.ChatCompletionsParams

class OpenAIChatModel(
    config: ModelConfig,
    transport: ModelTransport,
    params: OpenAIParams = OpenAIParams(),
    capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatCompletionsModel(
    config = config,
    transport = transport,
    providerId = ProviderId.OpenAI,
    params = ChatCompletionsParams(
        temperature = params.temperature,
        maxCompletionTokens = params.maxCompletionTokens,
        topP = params.topP,
        stop = params.stop,
        presencePenalty = params.presencePenalty,
        frequencyPenalty = params.frequencyPenalty,
        reasoningEffort = params.reasoningEffort,
    ),
    capabilities = capabilities,
)

data class OpenAIParams(
    val temperature: Double? = null,
    val maxCompletionTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val reasoningEffort: String? = null,
)
