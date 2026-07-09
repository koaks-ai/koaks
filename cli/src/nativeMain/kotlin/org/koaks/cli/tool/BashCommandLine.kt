package org.koaks.cli.tool

internal expect object BashCommandLine {
    fun build(command: String, outputPath: String): String
}
