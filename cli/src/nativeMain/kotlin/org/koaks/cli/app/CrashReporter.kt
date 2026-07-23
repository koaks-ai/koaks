package org.koaks.cli.app

import org.koaks.cli.tui.Output

internal fun formatCrashReport(error: Throwable): String {
    val type = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    val message = error.message?.takeIf { it.isNotBlank() }
    val header = if (message != null) {
        "[fatal] $type: $message"
    } else {
        "[fatal] $type"
    }
    return buildString {
        append(header)
        append('\n')
        append(error.stackTraceToString().trimEnd())
    }
}

internal fun printFatal(output: Output, error: Throwable) {
    output.writeLine(formatCrashReport(error))
    output.flush()
}
