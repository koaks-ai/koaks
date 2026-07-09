package org.koaks.cli.app.command

import org.koaks.cli.app.AgentContext
import org.koaks.cli.config.CliException
import org.koaks.cli.config.Provider
import org.koaks.cli.config.availableProviders
import org.koaks.cli.config.nonBlank
import org.koaks.cli.config.profileFor

internal fun builtinCommands(): List<SlashCommand> =
    listOf(
        HelpCommand,
        StatusCommand,
        ProviderCommand,
        ModelCommand,
        ReasoningCommand,
        ExitCommand,
    )

private object HelpCommand : SlashCommand {
    override val names: Set<String> = setOf("/help")
    override val description: String = "Show this help"

    override fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult {
        val nameWidth = registry.commands.maxOf { it.names.joinToString(", ").length }
        val lines = buildList {
            add("Commands:")
            registry.commands.forEach { command ->
                val names = command.names.joinToString(", ")
                add("  ${names.padEnd(nameWidth)}  ${command.description}")
            }
        }
        context.output.writeLine(lines.joinToString("\n") { context.theme.dim(it) })
        return CommandResult.Continue
    }
}

private object StatusCommand : SlashCommand {
    override val names: Set<String> = setOf("/status")
    override val description: String = "Show current session config"

    override fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult {
        val config = context.config
        context.output.writeLine(
            context.theme.dim(
                """
                Provider: ${config.provider.id}
                Model: ${config.modelName}
                Base URL: ${config.baseUrl}
                API key: ${maskKey(config.apiKey)}
                Thread: ${config.threadId}
                History: ${config.historyMessages} messages
                Reasoning: ${if (config.showReasoning) "on" else "off"}
                """.trimIndent()
            )
        )
        return CommandResult.Continue
    }
}

private object ProviderCommand : SlashCommand {
    override val names: Set<String> = setOf("/provider")
    override val description: String = "Show or set provider"

    override fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult {
        val value = args(input)
        if (value.isBlank()) {
            context.output.writeLine(
                context.theme.dim(
                    "Provider: ${context.config.provider.id}\n" +
                        "Available: ${context.config.availableProviders().joinToString(", ") { it.id }}"
                )
            )
            return CommandResult.Continue
        }

        try {
            val provider = Provider.parse(value)
            val profile = context.config.profileFor(provider)
            context.session.updateConfig { config ->
                config.copy(
                    provider = provider,
                    baseUrl = profile.baseUrl,
                    modelName = profile.defaultModel,
                    apiKey = profile.apiKey,
                )
            }
            context.output.writeLine(context.theme.dim("Provider set to ${provider.id}; model set to ${profile.defaultModel}."))
        } catch (e: CliException) {
            context.output.writeLine(context.theme.error("[error] ${e.message}"))
        }
        return CommandResult.Continue
    }
}

private object ModelCommand : SlashCommand {
    override val names: Set<String> = setOf("/model")
    override val description: String = "Show or set model"

    override fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult {
        val value = args(input)
        val profile = context.config.profileFor(context.config.provider)
        if (value.isBlank()) {
            val available = profile.modelList
            val message = if (available.isEmpty()) {
                "Model: ${context.config.modelName}"
            } else {
                "Model: ${context.config.modelName}\nAvailable: ${available.joinToString(", ")}"
            }
            context.output.writeLine(context.theme.dim(message))
            return CommandResult.Continue
        }

        try {
            val model = value.nonBlank("/model")
            if (profile.modelList.isNotEmpty() && model !in profile.modelList) {
                throw CliException("Unknown model '$model'. Expected ${profile.modelList.joinToString(", ")}.")
            }
            context.session.updateConfig { it.copy(modelName = model) }
            context.output.writeLine(context.theme.dim("Model set to $model."))
        } catch (e: CliException) {
            context.output.writeLine(context.theme.error("[error] ${e.message}"))
        }
        return CommandResult.Continue
    }
}

private object ReasoningCommand : SlashCommand {
    override val names: Set<String> = setOf("/reasoning")
    override val description: String = "Show or toggle reasoning"

    override fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult {
        when (val value = args(input).lowercase()) {
            "" -> context.output.writeLine(context.theme.dim("Reasoning: ${if (context.config.showReasoning) "on" else "off"}"))
            "on", "true", "1", "yes" -> {
                context.session.updateConfig { it.copy(showReasoning = true) }
                context.output.writeLine(context.theme.dim("Reasoning enabled."))
            }
            "off", "false", "0", "no" -> {
                context.session.updateConfig { it.copy(showReasoning = false) }
                context.output.writeLine(context.theme.dim("Reasoning disabled."))
            }
            else -> context.output.writeLine(context.theme.error("[error] Usage: /reasoning <on|off>"))
        }
        return CommandResult.Continue
    }
}

private object ExitCommand : SlashCommand {
    override val names: Set<String> = setOf("/exit", "/quit", ":q")
    override val description: String = "Quit the agent"

    override fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult =
        CommandResult.Exit
}

private fun args(input: String): String =
    input.substringAfter(" ", missingDelimiterValue = "").trim()

private fun maskKey(apiKey: String?): String {
    if (apiKey.isNullOrBlank()) return "(not set)"
    if (apiKey.length <= 8) return "********"
    return "${apiKey.take(4)}...${apiKey.takeLast(4)}"
}
