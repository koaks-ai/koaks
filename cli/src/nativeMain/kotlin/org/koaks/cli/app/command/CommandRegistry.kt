package org.koaks.cli.app.command

import org.koaks.cli.app.AgentContext

internal class CommandRegistry(commands: List<SlashCommand>) {
    val commands: List<SlashCommand> = commands.toList()
    private val byName: Map<String, SlashCommand> = commands
        .flatMap { command -> command.names.map { name -> name.normalizeName() to command } }
        .toMap()

    val suggestions: List<CommandSuggestion> = commands.flatMap { command ->
        command.names
            .filter { it.startsWith("/") }
            .map { name -> CommandSuggestion(name, command.description) }
    }

    val commandNames: Set<String> = byName.keys

    fun dispatch(input: String, context: AgentContext): CommandResult? {
        val name = input.trim().substringBefore(" ").normalizeName()
        byName[name]?.let { command ->
            return command.run(input, context, this)
        }

        if (looksLikeCommand(name)) {
            context.output.writeLine(context.theme.warn("[unknown command] $name. Type /help for commands."))
            return CommandResult.Continue
        }

        return null
    }

    private fun looksLikeCommand(name: String): Boolean =
        name.startsWith("/") || name.startsWith(":")

    fun isBuiltinCommand(name: String): Boolean =
        name.normalizeName() in byName

    companion object {
        fun builtins(): CommandRegistry = CommandRegistry(builtinCommands())
    }
}

internal data class CommandSuggestion(
    val name: String,
    val description: String,
)

private fun String.normalizeName(): String =
    if (startsWith("/")) lowercase() else this
