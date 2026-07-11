package org.koaks.cli.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koaks.cli.config.AgentConfig
import org.koaks.cli.config.CliException
import org.koaks.cli.config.Provider
import org.koaks.cli.tool.registerBuiltinCliTools
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.agent
import org.koaks.framework.middleware.AgentListener
import org.koaks.provider.anthropic.anthropic
import org.koaks.provider.ollama.ollama
import org.koaks.provider.openai.openai
import org.koaks.provider.qwen.qwen

internal object AgentFactory {
    fun build(config: AgentConfig, listener: AgentListener? = null): Agent {
        val apiKey = config.apiKey ?: missingApiKey(config.provider)
        return agent {
            name = "koaks-cli"
            instructions = config.instructions
            memory {
                window(config.historyMessages)
            }
            terminateAfter(maxSteps = 1024)
            listener?.let { install(it) }
            tools {
                registerBuiltinCliTools()
            }
            model {
                when (config.provider) {
                    Provider.OPENAI -> openai(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        modelName = config.modelName,
                    ) {
                        temperature = config.temperature
                    }

                    Provider.QWEN -> qwen(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        modelName = config.modelName,
                    ) {
                        temperature = config.temperature
                        enableThinking = config.showReasoning
                    }

                    Provider.ANTHROPIC -> anthropic(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        modelName = config.modelName,
                    ) {
                        temperature = config.temperature
                        if (config.showReasoning) {
                            thinking = anthropicThinking()
                        }
                    }

                    Provider.OLLAMA -> ollama(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        modelName = config.modelName,
                    ) {
                        temperature = config.temperature
                        think = config.showReasoning
                    }
                }
            }
        }
    }

    private fun missingApiKey(provider: Provider): Nothing {
        throw CliException("Missing API key for ${provider.id}. Add api_key under [providers.${provider.id}] in ~/.koaks/config.toml.")
    }

    private fun anthropicThinking() = buildJsonObject {
        put("type", "enabled")
        put("budget_tokens", ANTHROPIC_THINKING_BUDGET_TOKENS)
    }

    private const val ANTHROPIC_THINKING_BUDGET_TOKENS = 1024
}
