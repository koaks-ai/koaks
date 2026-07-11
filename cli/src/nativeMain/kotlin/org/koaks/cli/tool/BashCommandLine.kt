package org.koaks.cli.tool

internal expect object BashCommandLine {
    val toolName: String
    val shellName: String
    val commandSyntaxGuidance: String

    fun execute(command: String, maxOutputChars: Int): CommandResult
}
