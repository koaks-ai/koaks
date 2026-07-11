package org.koaks.cli.tool

internal expect object BashCommandLine {
    val shellName: String
    val commandSyntaxGuidance: String

    fun execute(command: String, maxOutputChars: Int): CommandResult
}
