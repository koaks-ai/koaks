package org.koaks.cli.config

internal object ConfigResolver {
    fun resolve(env: Environment): AgentConfig {
        return resolve(ConfigFileLoader.load(env))
    }

    fun resolve(fileConfig: FileConfig): AgentConfig {
        val profiles = buildProfiles(fileConfig)
        val provider = fileConfig.defaultProvider ?: ProviderCatalog.infer(fileConfig.providerOrder)

        val profile = profiles.getValue(provider)

        val model = fileConfig.defaultModel ?: profile.defaultModel
        val history = fileConfig.historyMessages ?: DEFAULT_HISTORY_MESSAGES

        return AgentConfig(
            provider = provider,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            modelName = model,
            instructions = fileConfig.instructions ?: DEFAULT_INSTRUCTIONS.trim(),
            threadId = fileConfig.threadId ?: DEFAULT_THREAD_ID,
            historyMessages = history,
            temperature = fileConfig.temperature,
            showReasoning = fileConfig.showReasoning ?: false,
            providerProfiles = profiles,
            configuredProviders = fileConfig.providerOrder,
        )
    }

    private fun buildProfiles(fileConfig: FileConfig): Map<Provider, ProviderProfile> =
        Provider.entries.associateWith { provider ->
            val providerConfig = fileConfig.providers[provider]
            ProviderProfile(
                provider = provider,
                baseUrl = providerConfig?.baseUrl ?: provider.defaultBaseUrl,
                apiKey = providerConfig?.apiKey ?: if (provider == Provider.OLLAMA) "ollama" else null,
                defaultModel = providerConfig?.modelOrDefault(provider) ?: provider.defaultModel,
                modelList = providerConfig?.modelList.orEmpty(),
            )
        }
}
