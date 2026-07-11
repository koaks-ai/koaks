@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.tool

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.random.Random
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.remove
import platform.posix.system

internal actual object BashCommandLine {
    actual val toolName: String = "Bash"
    actual val shellName: String = "Bash (`bash`)"
    actual val commandSyntaxGuidance: String = "Use Bash command syntax."

    actual fun execute(command: String, maxOutputChars: Int): CommandResult {
        val outputPath = ".koaks-bash-output-${Random.nextInt(0, Int.MAX_VALUE)}.log"
        val status = system("bash -lc ${singleQuote(command)} > ${singleQuote(outputPath)} 2>&1")
        val output = readOutput(outputPath, maxOutputChars)
        remove(outputPath)
        return CommandResult(
            status = status,
            output = output.text.ifEmpty {
                if (status == 0) "" else "Unable to read command output. Make sure $shellName is installed and available on PATH."
            },
            totalOutputChars = output.totalChars,
            truncated = output.truncated,
        )
    }
}

private fun singleQuote(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

private fun readOutput(path: String, maxChars: Int): AppleRawOutput {
    val file = fopen(path, "rb") ?: return AppleRawOutput("", 0, false)
    val captured = StringBuilder()
    var totalChars = 0
    var truncated = false
    try {
        memScoped {
            val buffer = allocArray<ByteVar>(8192)
            while (fgets(buffer, 8192, file) != null) {
                val chunk = buffer.toKString()
                totalChars += chunk.length
                val remaining = maxChars - captured.length
                if (remaining > 0) captured.append(chunk.take(remaining))
                if (chunk.length > remaining) truncated = true
            }
        }
    } finally {
        fclose(file)
    }
    return AppleRawOutput(captured.toString(), totalChars, truncated)
}

private data class AppleRawOutput(
    val text: String,
    val totalChars: Int,
    val truncated: Boolean,
)
