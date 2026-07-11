package org.koaks.cli.tool

internal expect object BashCommandLine {
    val shellName: String
    val commandSyntaxGuidance: String

    fun build(command: String, outputPath: String): String
}
