package org.koaks.cli.tool

internal actual object BashCommandLine {
    actual fun build(command: String, outputPath: String): String =
        "bash -lc ${cmdQuote(command)} > ${cmdQuote(outputPath)} 2>&1"
}

private fun cmdQuote(value: String): String =
    "\"" + value
        .replace("%", "%%")
        .replace("\"", "\\\"") + "\""
