package org.koaks.cli.tool

import kotlinx.serialization.Serializable
import org.koaks.framework.loop.ToolScope
import org.koaks.framework.tool.Tool

internal fun ToolScope.registerBuiltinCliTools() {
    tool(BashTool)
    tool(ReadTool)
}

@Serializable
internal data class BashInput(
    val command: String,
)

internal object BashTool : Tool<BashInput> {
    override val name: String = "Bash"
    override val description: String =
        "Run a shell command from the current working directory. " +
            "Current operating system: $currentOperatingSystemName. " +
            "Current shell: ${BashCommandLine.shellName}. " +
            "${BashCommandLine.commandSyntaxGuidance} " +
            "Use this for inspecting the project, running tests, and other shell tasks. " +
            "The command's stdout and stderr are returned, with long output truncated."
    override val inputSerializer = BashInput.serializer()
    override val hasSideEffects: Boolean = true

    override suspend fun execute(input: BashInput): String {
        val command = input.command.trim()
        if (command.isEmpty()) return "Error: command is required."

        val result = NativeCliIo.runBash(command, MAX_BASH_OUTPUT_CHARS)
        return buildString {
            appendLine("Command: $command")
            appendLine("Stats: Status=${result.status}")
            if (result.output.isBlank()) {
                appendLine("Output: <empty>")
            } else {
                appendLine("=== Output ===")
                append(result.output.trimEnd())
                appendLine()
            }
            if (result.truncated) {
                appendLine(
                    "[output truncated to $MAX_BASH_OUTPUT_CHARS of ${result.totalOutputChars} characters]"
                )
            }
        }.trimEnd()
    }
}

@Serializable
internal data class ReadInput(
    val path: String,
    val offset: Int? = null,
    val limit: Int? = null,
)

internal object ReadTool : Tool<ReadInput> {
    override val name: String = "Read"
    override val description: String =
        "Read a code or text file and return content with line numbers. " +
            "`path` may be relative to the current working directory. " +
            "`offset` is a 1-based starting line, and `limit` is the number of lines to read. " +
            "When no range is provided and the file is large, only total line and character counts are returned."
    override val inputSerializer = ReadInput.serializer()

    override suspend fun execute(input: ReadInput): String {
        val path = input.path.trim()
        if (path.isEmpty()) return "Error: path is required."

        val hasExplicitWindow = input.offset != null || input.limit != null
        val offset = input.offset ?: 1
        if (offset < 1) return "Error: offset must be 1 or greater."

        val requestedLimit = input.limit ?: if (hasExplicitWindow) DEFAULT_READ_WINDOW_LINES else MAX_AUTO_READ_LINES
        if (requestedLimit < 1) return "Error: limit must be 1 or greater."

        val effectiveLimit = requestedLimit.coerceAtMost(MAX_READ_WINDOW_LINES)
        val maxChars = if (hasExplicitWindow) MAX_READ_WINDOW_CHARS else MAX_AUTO_READ_CHARS
        val scan = NativeCliIo.readTextWindow(
            path = path,
            offset = offset,
            limit = effectiveLimit,
            maxCapturedChars = maxChars,
        )

        if (scan.error != null) return "Error: ${scan.error}"

        if (!hasExplicitWindow && scan.isTooLargeForAutomaticRead()) {
            return buildLargeFileSummary(path, scan)
        }

        return buildReadOutput(
            path = path,
            scan = scan,
            offset = offset,
            requestedLimit = requestedLimit,
            effectiveLimit = effectiveLimit,
        )
    }

    private fun buildLargeFileSummary(path: String, scan: TextWindowScan): String = buildString {
        appendLine("Path: $path")
        appendLine("Stats: Total Lines=${scan.totalLines}  Total Chars=${scan.totalChars}")
        append("File is too large to return automatically; ")
        append("use offset=1 and limit=$DEFAULT_READ_WINDOW_LINES to read a window.")
    }.trimEnd()

    private fun buildReadOutput(
        path: String,
        scan: TextWindowScan,
        offset: Int,
        requestedLimit: Int,
        effectiveLimit: Int,
    ): String = buildString {
        val firstLine = scan.lines.firstOrNull()?.number
        val lastLine = scan.lines.lastOrNull()?.number
        appendLine("Path: $path")
        append("Stats: Total Lines=${scan.totalLines}")
        append("  Total Chars=${scan.totalChars}")
        if (firstLine == null || lastLine == null) {
            appendLine("  Requested=$offset-${offset + effectiveLimit - 1}")
            appendLine("No lines in requested range.")
            return@buildString
        }

        appendLine("  Showing=$firstLine-$lastLine")
        if (requestedLimit != effectiveLimit) {
            appendLine("[requested limit $requestedLimit was capped to $effectiveLimit lines]")
        }
        if (scan.truncatedByChars) {
            appendLine("[content truncated at $MAX_READ_WINDOW_CHARS characters for this read]")
        }
        appendLine()
        append(formatNumberedLines(scan.lines, scan.totalLines))
    }.trimEnd()

    private fun TextWindowScan.isTooLargeForAutomaticRead(): Boolean =
        totalLines > MAX_AUTO_READ_LINES ||
            totalChars > MAX_AUTO_READ_CHARS ||
            truncatedByChars
}

private fun formatNumberedLines(lines: List<NumberedTextLine>, totalLines: Long): String {
    val width = maxOf(totalLines.toString().length, lines.lastOrNull()?.number?.toString()?.length ?: 1)
    return lines.joinToString("\n") { line ->
        "${line.number.toString().padStart(width)} | ${line.text}"
    }
}

private const val DEFAULT_READ_WINDOW_LINES = 200
private const val MAX_AUTO_READ_LINES = 400
private const val MAX_READ_WINDOW_LINES = 400
private const val MAX_AUTO_READ_CHARS = 30_000
private const val MAX_READ_WINDOW_CHARS = 30_000
private const val MAX_BASH_OUTPUT_CHARS = 30_000
