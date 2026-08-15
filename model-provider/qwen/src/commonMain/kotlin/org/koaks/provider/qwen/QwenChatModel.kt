package org.koaks.provider.qwen

import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ProviderId
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.transport.ModelTransport
import org.koaks.provider.chatcompletions.ChatCompletionsModel
import org.koaks.provider.chatcompletions.ChatCompletionsParams

class QwenChatModel(
    config: ModelConfig,
    transport: ModelTransport,
    params: QwenParams = QwenParams(),
    capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatCompletionsModel(
    config = config,
    transport = transport,
    providerId = ProviderId.Qwen,
    params = ChatCompletionsParams(
        temperature = params.temperature,
        maxTokens = params.maxTokens,
        topP = params.topP,
        stop = params.stop,
        presencePenalty = params.presencePenalty,
        frequencyPenalty = params.frequencyPenalty,
        enableThinking = params.enableThinking,
    ),
    capabilities = capabilities,
)

data class QwenParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val enableThinking: Boolean? = null,
)
