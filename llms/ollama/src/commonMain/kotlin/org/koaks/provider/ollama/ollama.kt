package org.koaks.provider.ollama

import org.koaks.framework.api.dsl.ModelSelector

fun ModelSelector.ollama(
    baseUrl: String,
    apiKey: String = "ollama",
    modelName: String,
    block: (OllamaChatModel.() -> Unit)? = null
): OllamaChatModel {
    val model = OllamaChatModel(baseUrl, apiKey, modelName)
    block?.invoke(model)
    this.selected = model
    return model
}