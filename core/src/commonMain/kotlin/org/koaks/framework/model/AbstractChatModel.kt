package org.koaks.framework.model

import kotlinx.serialization.KSerializer
import org.koaks.framework.entity.ModelParams
import org.koaks.framework.model.adapter.ChatModelAdapter

abstract class AbstractChatModel<TRequest, TResponse>(
    open val baseUrl: String,
    open val apiKey: String,
    open val modelName: String,
) : ModelParams(), ChatModelAdapter<TRequest, TResponse> {

    abstract val responseDeserializer: KSerializer<TResponse>

}
