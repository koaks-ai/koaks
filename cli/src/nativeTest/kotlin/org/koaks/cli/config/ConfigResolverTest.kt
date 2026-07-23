package org.koaks.cli.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigResolverTest {
    @Test
    fun usesFileConfigForStartupConfig() {
        val fileConfig = FileConfig(
            defaultProvider = Provider.OPENAI,
            defaultModel = "gpt-file",
            instructions = "file instructions",
            threadId = "file-thread",
            historyMessages = 7,
            temperature = 0.4,
            showReasoning = true,
            skillPaths = listOf(".agents/skills"),
            skills = listOf("code-review"),
            providers = mapOf(
                Provider.OPENAI to FileProviderConfig(
                    baseUrl = "https://openai.config",
                    apiKey = "file-key",
                    modelList = listOf("gpt-file", "gpt-other"),
                )
            ),
            providerOrder = listOf(Provider.OPENAI),
        )

        val config = ConfigResolver.resolve(fileConfig)

        assertEquals(Provider.OPENAI, config.provider)
        assertEquals("https://openai.config", config.baseUrl)
        assertEquals("file-key", config.apiKey)
        assertEquals("gpt-file", config.modelName)
        assertEquals("file instructions", config.instructions)
        assertEquals("file-thread", config.threadId)
        assertEquals(7, config.historyMessages)
        assertEquals(0.4, config.temperature)
        assertEquals(true, config.showReasoning)
        assertEquals(listOf(".agents/skills"), config.skillPaths)
        assertEquals(listOf("code-review"), config.skills)
        assertEquals(listOf("gpt-file", "gpt-other"), config.profileFor(Provider.OPENAI).modelList)
    }

    @Test
    fun profileValuesComeFromFileConfig() {
        val fileConfig = FileConfig(
            defaultProvider = Provider.OPENAI,
            providers = mapOf(
                Provider.OPENAI to FileProviderConfig(
                    baseUrl = "https://file.example",
                    apiKey = "file-key",
                    modelList = listOf("file-model"),
                ),
                Provider.ANTHROPIC to FileProviderConfig(
                    apiKey = "anthropic-file-key",
                    modelList = listOf("claude-file"),
                ),
            ),
            providerOrder = listOf(Provider.OPENAI, Provider.ANTHROPIC),
        )

        val config = ConfigResolver.resolve(fileConfig)

        assertEquals(Provider.OPENAI, config.provider)
        assertEquals("https://file.example", config.baseUrl)
        assertEquals("file-key", config.apiKey)
        assertEquals("file-model", config.modelName)
    }

    @Test
    fun usesFirstConfiguredProviderWhenDefaultProviderIsAbsent() {
        val fileConfig = FileConfig(
            providers = mapOf(
                Provider.ANTHROPIC to FileProviderConfig(modelList = listOf("claude-file")),
                Provider.OPENAI to FileProviderConfig(modelList = listOf("gpt-file")),
            ),
            providerOrder = listOf(Provider.ANTHROPIC, Provider.OPENAI),
        )

        val config = ConfigResolver.resolve(fileConfig)

        assertEquals(Provider.ANTHROPIC, config.provider)
        assertEquals("claude-file", config.modelName)
    }

    @Test
    fun ollamaCanRunWithoutApiKey() {
        val config = ConfigResolver.resolve(FileConfig(defaultProvider = Provider.OLLAMA))

        assertEquals(Provider.OLLAMA, config.provider)
        assertEquals("ollama", config.apiKey)
    }
}
