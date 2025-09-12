package org.koaks.provider.qwen

import org.koaks.framework.api.dsl.ModelSelector

fun ModelSelector.qwen(
    baseUrl: String,
    apiKey: String,
    modelName: String,
    block: (QwenChatModel.() -> Unit)? = null
): QwenChatModel {
    val model = QwenChatModel(baseUrl, apiKey, modelName)
    block?.invoke(model)
    this.selected = model
    return model
}