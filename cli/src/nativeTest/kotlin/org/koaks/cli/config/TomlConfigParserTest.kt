package org.koaks.cli.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TomlConfigParserTest {
    @Test
    fun parsesProviderProfiles() {
        val config = TomlConfigParser.parse(
            """
            provider = "openai"
            model = "gpt-4.1-mini"
            show_reasoning = true
            skill_paths = [".agents/skills", "~/.koaks/skills"]
            skills = ["code-review"]

            [providers.openai]
            base_url = "https://api.openai.example/v1/chat/completions"
            api_key = "openai-key"
            model_list = ["gpt-4.1-mini", "gpt-4.1"]

            [providers.anthropic]
            base_url = "https://api.anthropic.example/v1/messages"
            api_key = "anthropic-key"
            model_list = [
              "claude-sonnet-4-20250514",
              "claude-opus-4-20250514",
            ]
            """.trimIndent()
        )

        assertEquals(Provider.OPENAI, config.defaultProvider)
        assertEquals("gpt-4.1-mini", config.defaultModel)
        assertEquals(true, config.showReasoning)
        assertEquals(listOf(".agents/skills", "~/.koaks/skills"), config.skillPaths)
        assertEquals(listOf("code-review"), config.skills)
        assertEquals(listOf(Provider.OPENAI, Provider.ANTHROPIC), config.providerOrder)
        assertEquals("openai-key", config.providers[Provider.OPENAI]?.apiKey)
        assertEquals(
            listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514"),
            config.providers[Provider.ANTHROPIC]?.modelList,
        )
    }

    @Test
    fun rejectsUnknownProvider() {
        val error = assertFailsWith<CliException> {
            TomlConfigParser.parse(
                """
                [providers.nope]
                api_key = "key"
                """.trimIndent()
            )
        }

        assertTrue(error.message!!.contains("Unknown provider"))
    }
}
