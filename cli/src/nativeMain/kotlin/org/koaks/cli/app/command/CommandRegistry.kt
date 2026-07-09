package org.koaks.cli.app.command

import org.koaks.cli.app.AgentContext

internal class CommandRegistry(commands: List<SlashCommand>) {
    val commands: List<SlashCommand> = commands.toList()
    private val byName: Map<String, SlashCommand> = commands
        .flatMap { command -> command.names.map { name -> name.normalizeName() to command } }
        .toMap()

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

    companion object {
        fun builtins(): CommandRegistry = CommandRegistry(builtinCommands())
    }
}

private fun String.normalizeName(): String =
    if (startsWith("/")) lowercase() else this
