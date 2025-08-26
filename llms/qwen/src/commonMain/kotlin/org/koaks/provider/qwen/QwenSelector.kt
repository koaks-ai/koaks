package org.koaks.provider.qwen

import org.koaks.framework.api.dsl.ModelSelector

fun ModelSelector.qwen(
    baseUrl: String,
    apiKey: String,
    modelName: String
): QwenChatModel {
    val model = QwenChatModel(baseUrl, apiKey, modelName)
    this.selected = model
    return model
}