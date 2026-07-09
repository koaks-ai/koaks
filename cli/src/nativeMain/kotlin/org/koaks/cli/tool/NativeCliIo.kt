@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.tool

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.random.Random
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.remove
import platform.posix.system

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
    fun runBash(command: String, maxOutputChars: Int): CommandResult {
        val outputPath = temporaryOutputPath()
        val status = system(BashCommandLine.build(command, outputPath))
        val output = readRawText(path = outputPath, maxChars = maxOutputChars)
        remove(outputPath)

        return CommandResult(
            status = status,
            output = output.text.ifEmpty {
                if (status == 0) "" else "Unable to read command output. Make sure `bash` is installed and available on PATH."
            },
            totalOutputChars = output.totalChars,
            truncated = output.truncated,
        )
    }

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

    private fun readRawText(path: String, maxChars: Int): RawTextRead {
        val file = fopen(path, "rb") ?: return RawTextRead("", 0, false)
        val output = StringBuilder()
        var totalChars = 0
        var truncated = false
        try {
            readChunks(file) { chunk ->
                totalChars += chunk.length
                val remaining = maxChars - output.length
                if (remaining > 0) {
                    output.append(chunk.take(remaining))
                }
                if (chunk.length > remaining) truncated = true
            }
        } finally {
            fclose(file)
        }
        return RawTextRead(output.toString(), totalChars, truncated)
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

private data class RawTextRead(
    val text: String,
    val totalChars: Int,
    val truncated: Boolean,
)

private fun temporaryOutputPath(): String =
    ".koaks-bash-output-${Random.nextInt(0, Int.MAX_VALUE)}.log"
