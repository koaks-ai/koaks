package org.koaks.cli.config

import platform.posix.remove
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            assertEquals(false, config.showReasoning)
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
        assertEquals(false, config.showReasoning)
        assertEquals("gpt-5.5", config.providers[Provider.OPENAI]?.modelOrDefault(Provider.OPENAI))
        assertEquals("claude-opus-4-8", config.providers[Provider.ANTHROPIC]?.modelOrDefault(Provider.ANTHROPIC))
    }

    @Test
    fun expandsHomeRelativeSkillPaths() {
        val env = TestEnvironment("HOME" to "/users/reviewer")

        assertEquals(
            "/users/reviewer/.koaks/skills",
            ConfigFileLoader.expandSkillPath("~/.koaks/skills", env),
        )
        assertEquals(".agents/skills", ConfigFileLoader.expandSkillPath(".agents/skills", env))
    }

    @Test
    fun rejectsNamedUserHomeExpansion() {
        assertFailsWith<CliException> {
            ConfigFileLoader.expandSkillPath("~other/skills", TestEnvironment("HOME" to "/users/current"))
        }
    }
}

private class TestEnvironment(
    private val entries: Map<String, String>,
) : Environment {
    constructor(vararg pairs: Pair<String, String>) : this(mapOf(*pairs))

    override fun get(key: String): String? = entries[key]
}
