package org.koaks.framework.model

import org.koaks.framework.entity.ModelParams
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.FullChatRequest
import org.koaks.framework.toolcall.ToolDefinition

abstract class AbstractChatModel<TRequest, TResponse>(
    open val baseUrl: String,
    open val apiKey: String,
    open val modelName: String,
) : ModelParams() {

    val toolContainer: HashMap<String, ToolDefinition> = HashMap()

    abstract val typeAdapter: TypeAdapter<TRequest, TResponse>

    abstract fun toChatRequest(fullChatRequest: FullChatRequest): TRequest

    abstract fun toChatResponse(providerResponse: TResponse): ChatResponse

}
