package org.koaks.cli.config

import platform.posix.remove
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigFileLoaderTest {
    @Test
    fun createsDefaultConfigWhenMissing() {
        val home = ".koaks-config-loader-test-${Random.nextInt(0, Int.MAX_VALUE)}"
        val directory = "$home/.koaks"
        val path = "$directory/config.toml"

        try {
            ConfigFileSystem.createDirectory(home)
            val config = ConfigFileLoader.load(TestEnvironment("HOME" to home))

            assertEquals(Provider.OPENAI, config.defaultProvider)
            assertEquals(listOf(Provider.OPENAI, Provider.ANTHROPIC), config.providerOrder)
            assertEquals("gpt-5.5", config.providers[Provider.OPENAI]?.defaultModel)
            assertEquals(listOf("gpt-5.5"), config.providers[Provider.OPENAI]?.modelList)
            assertEquals("claude-opus-4-8", config.providers[Provider.ANTHROPIC]?.defaultModel)
            assertEquals(listOf("claude-opus-4-8"), config.providers[Provider.ANTHROPIC]?.modelList)
        } finally {
            remove(path)
            rmdir(directory)
            rmdir(home)
        }
    }

    @Test
    fun defaultConfigTextParsesToRequestedModels() {
        val config = TomlConfigParser.parse(ConfigFileLoader.defaultConfigText())

        assertEquals(Provider.OPENAI, config.defaultProvider)
        assertEquals("gpt-5.5", config.providers[Provider.OPENAI]?.modelOrDefault(Provider.OPENAI))
        assertEquals("claude-opus-4-8", config.providers[Provider.ANTHROPIC]?.modelOrDefault(Provider.ANTHROPIC))
    }
}

private class TestEnvironment(
    private val entries: Map<String, String>,
) : Environment {
    constructor(vararg pairs: Pair<String, String>) : this(mapOf(*pairs))

    override fun get(key: String): String? = entries[key]
}
