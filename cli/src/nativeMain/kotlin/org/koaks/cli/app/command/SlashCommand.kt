package org.koaks.cli.app.command

import org.koaks.cli.app.AgentContext

internal sealed interface CommandResult {
    object Continue : CommandResult
    object Exit : CommandResult
}

internal interface SlashCommand {
    val names: Set<String>
    val description: String

    fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult
}
