package org.koaks.cli.config

internal const val DEFAULT_THREAD_ID = "koaks-cli"
internal const val DEFAULT_HISTORY_MESSAGES = 40

internal const val DEFAULT_INSTRUCTIONS = """
You are Koaks CLI, a concise and helpful terminal agent.
Answer directly, keep formatting readable in a terminal, and ask a short clarifying question when needed.
"""

internal data class AgentConfig(
    val provider: Provider,
    val baseUrl: String,
    val apiKey: String?,
    val modelName: String,
    val instructions: String,
    val threadId: String,
    val historyMessages: Int,
    val temperature: Double?,
    val showReasoning: Boolean,
    val providerProfiles: Map<Provider, ProviderProfile>,
    val configuredProviders: List<Provider>,
)

internal data class ProviderProfile(
    val provider: Provider,
    val baseUrl: String,
    val apiKey: String?,
    val defaultModel: String,
    val modelList: List<String>,
)

internal fun AgentConfig.profileFor(provider: Provider): ProviderProfile =
    providerProfiles[provider] ?: ProviderProfile(
        provider = provider,
        baseUrl = provider.defaultBaseUrl,
        apiKey = if (provider == Provider.OLLAMA) "ollama" else null,
        defaultModel = provider.defaultModel,
        modelList = emptyList(),
    )

internal fun AgentConfig.availableProviders(): List<Provider> =
    configuredProviders.ifEmpty { Provider.entries.toList() }
