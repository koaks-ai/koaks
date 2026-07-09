package org.koaks.cli.config

internal data class FileConfig(
    val defaultProvider: Provider? = null,
    val defaultModel: String? = null,
    val instructions: String? = null,
    val threadId: String? = null,
    val historyMessages: Int? = null,
    val temperature: Double? = null,
    val showReasoning: Boolean? = null,
    val providers: Map<Provider, FileProviderConfig> = emptyMap(),
    val providerOrder: List<Provider> = emptyList(),
) {
    companion object {
        val Empty = FileConfig()
    }
}

internal data class FileProviderConfig(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val defaultModel: String? = null,
    val modelList: List<String> = emptyList(),
) {
    fun modelOrDefault(provider: Provider): String =
        defaultModel ?: modelList.firstOrNull() ?: provider.defaultModel
}
