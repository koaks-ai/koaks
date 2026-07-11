package org.koaks.cli.tool

internal actual object BashCommandLine {
    actual val shellName: String = "Bash (`bash`)"
    actual val commandSyntaxGuidance: String = "Use Bash command syntax."

    actual fun build(command: String, outputPath: String): String =
        "bash -lc ${singleQuote(command)} > ${singleQuote(outputPath)} 2>&1"
}

private fun singleQuote(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"
