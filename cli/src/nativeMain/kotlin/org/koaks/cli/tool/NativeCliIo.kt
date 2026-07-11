@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.tool

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

internal data class CommandResult(
    val status: Int,
    val output: String,
    val totalOutputChars: Int,
    val truncated: Boolean,
)

internal data class NumberedTextLine(
    val number: Long,
    val text: String,
)

internal data class TextWindowScan(
    val totalLines: Long,
    val totalChars: Long,
    val lines: List<NumberedTextLine>,
    val truncatedByChars: Boolean,
    val error: String? = null,
)

internal object NativeCliIo {
    fun runBash(command: String, maxOutputChars: Int): CommandResult =
        BashCommandLine.execute(command, maxOutputChars)

    fun readTextWindow(
        path: String,
        offset: Int,
        limit: Int,
        maxCapturedChars: Int,
    ): TextWindowScan {
        val file = fopen(path, "rb")
            ?: return TextWindowScan(
                totalLines = 0,
                totalChars = 0,
                lines = emptyList(),
                truncatedByChars = false,
                error = "unable to open file: $path",
            )

        return try {
            scanFile(file, offset.toLong(), limit, maxCapturedChars)
        } finally {
            fclose(file)
        }
    }

    private fun readChunks(file: kotlinx.cinterop.CPointer<FILE>, onChunk: (String) -> Unit) {
        memScoped {
            val buffer = allocArray<ByteVar>(IO_BUFFER_SIZE)
            while (fgets(buffer, IO_BUFFER_SIZE, file) != null) {
                onChunk(buffer.toKString())
            }
        }
    }

    private fun scanFile(
        file: kotlinx.cinterop.CPointer<FILE>,
        offset: Long,
        limit: Int,
        maxCapturedChars: Int,
    ): TextWindowScan {
        val captured = mutableListOf<NumberedTextLine>()
        val line = StringBuilder()
        var totalLines = 0L
        var totalChars = 0L
        var capturedChars = 0
        var truncatedByChars = false
        var captureClosed = false

        fun capture(lineNumber: Long, text: String) {
            if (captureClosed || lineNumber < offset || captured.size >= limit) return

            val remaining = maxCapturedChars - capturedChars
            if (remaining <= 0) {
                truncatedByChars = true
                captureClosed = true
                return
            }

            val capturedText = text.take(remaining)
            captured += NumberedTextLine(lineNumber, capturedText)
            capturedChars += capturedText.length
            if (capturedText.length < text.length) {
                truncatedByChars = true
                captureClosed = true
            }
        }

        fun completeLine(raw: String) {
            totalLines += 1
            capture(totalLines, raw.removeSuffix("\n").removeSuffix("\r"))
        }

        memScoped {
            val buffer = allocArray<ByteVar>(IO_BUFFER_SIZE)
            while (fgets(buffer, IO_BUFFER_SIZE, file) != null) {
                val chunk = buffer.toKString()
                totalChars += chunk.length
                line.append(chunk)
                if (chunk.endsWith("\n")) {
                    completeLine(line.toString())
                    line.clear()
                }
            }
        }

        if (line.isNotEmpty()) {
            completeLine(line.toString())
        }

        return TextWindowScan(
            totalLines = totalLines,
            totalChars = totalChars,
            lines = captured,
            truncatedByChars = truncatedByChars,
        )
    }
}

private const val IO_BUFFER_SIZE = 8192
