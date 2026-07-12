@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.tool

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

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

internal data class FileContent(
    val text: String?,
    val totalBytes: Long,
    val error: String? = null,
)

internal data class FileWriteResult(
    val bytesWritten: Long,
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

    /** Returns whether a readable file exists at [path]. */
    fun fileExists(path: String): Boolean {
        val file = fopen(path, "rb") ?: return false
        fclose(file)
        return true
    }

    /**
     * Reads the whole file as UTF-8 text. Fails (without loading everything into
     * memory) when the byte size exceeds [maxBytes] so a runaway file can't blow
     * up the process. Bytes are accumulated raw and decoded once to avoid
     * splitting a multi-byte UTF-8 sequence across read boundaries.
     */
    fun readWholeFile(path: String, maxBytes: Long): FileContent {
        val file = fopen(path, "rb")
            ?: return FileContent(text = null, totalBytes = 0, error = "unable to open file: $path")

        return try {
            val chunks = ArrayList<ByteArray>()
            var total = 0L
            var tooLarge = false
            memScoped {
                val buffer = allocArray<ByteVar>(IO_BUFFER_SIZE)
                while (true) {
                    val read = fread(buffer, 1u.convert(), IO_BUFFER_SIZE.convert(), file).toLong()
                    if (read <= 0L) break
                    chunks.add(buffer.readBytes(read.toInt()))
                    total += read
                    if (total > maxBytes) {
                        tooLarge = true
                        break
                    }
                }
            }

            if (tooLarge) {
                return FileContent(
                    text = null,
                    totalBytes = total,
                    error = "file is too large to edit (over $maxBytes bytes): $path",
                )
            }

            val combined = ByteArray(total.toInt())
            var pos = 0
            for (chunk in chunks) {
                chunk.copyInto(combined, pos)
                pos += chunk.size
            }
            FileContent(text = combined.decodeToString(), totalBytes = total)
        } finally {
            fclose(file)
        }
    }

    /**
     * Writes [content] as UTF-8 to [path] atomically: it first writes to a
     * temporary sibling file and then renames it over the target, so a failure
     * mid-write cannot corrupt an existing file. On platforms where rename won't
     * overwrite (Windows), the existing target is removed first.
     */
    fun writeWholeFile(path: String, content: String): FileWriteResult {
        val bytes = content.encodeToByteArray()
        val tmpPath = "$path.koaks-tmp"

        val out = fopen(tmpPath, "wb")
            ?: return FileWriteResult(bytesWritten = 0, error = "unable to open file for writing: $path")

        val wroteAll = try {
            if (bytes.isEmpty()) {
                true
            } else {
                val written = bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), out).toLong()
                }
                written == bytes.size.toLong()
            }
        } finally {
            fclose(out)
        }

        if (!wroteAll) {
            remove(tmpPath)
            return FileWriteResult(bytesWritten = 0, error = "failed to write file: $path")
        }

        if (rename(tmpPath, path) != 0) {
            remove(path)
            if (rename(tmpPath, path) != 0) {
                remove(tmpPath)
                return FileWriteResult(bytesWritten = 0, error = "failed to replace file: $path")
            }
        }

        return FileWriteResult(bytesWritten = bytes.size.toLong())
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
