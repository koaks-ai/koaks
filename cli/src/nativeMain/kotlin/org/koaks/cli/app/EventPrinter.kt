package org.koaks.cli.app

import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalMarkdownRenderer
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme
import org.koaks.framework.loop.AgentEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class EventPrinter(
    private val showReasoning: Boolean,
    private val output: Output,
    private val theme: Theme,
) {
    private var assistantPromptActive = false
    private var assistantPromptPrinted = false
    private var reasoningPromptActive = false
    private var thinkingPlaceholderShown = false
    private var needsAssistantContinuationGap = false
    private var contentStarted = false
    private var endedWithNewLine = false
    private var hasFlushedStreamingContent = false
    private var unflushedStreamingChars = 0
    private var lastStreamingFlush = TimeSource.Monotonic.markNow()
    private val markdown = TerminalMarkdownRenderer(theme, PANEL_WIDTH)

    fun print(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                ensureAssistantPrompt()
                val rendered = markdown.render(event.text)
                markContent(rendered)
                output.write(rendered)
                flushStreamingOutputIfNeeded(rendered)
            }

            is AgentEvent.ReasoningDelta -> {
                if (showReasoning) {
                    ensureReasoningPrompt()
                    markContent(event.text)
                    val rendered = theme.dim(event.text)
                    output.write(rendered)
                    flushStreamingOutputIfNeeded(rendered)
                } else {
                    ensureThinkingPlaceholder()
                }
            }

            is AgentEvent.Completed -> {
                flushAssistantMarkdown()
                clearThinkingPlaceholder()
                if (!endedWithNewLine) output.writeLine()
                flushOutput()
            }

            is AgentEvent.Terminated -> {
                flushAssistantMarkdown()
                clearThinkingPlaceholder()
                if (!endedWithNewLine) output.writeLine()
                output.writeLine(theme.warn("[terminated] ${event.reason}"))
                flushOutput()
            }

            is AgentEvent.Failed -> {
                flushAssistantMarkdown()
                clearThinkingPlaceholder()
                ensureLineStart()
                output.writeLine(theme.error("[error] ${event.error.message}"))
                flushOutput()
            }

            is AgentEvent.ToolCallRequested -> printToolCall(event)
            is AgentEvent.ToolResult -> printToolResult(event)
            is AgentEvent.StepCompleted -> Unit
        }
    }

    private fun printToolCall(event: AgentEvent.ToolCallRequested) {
        flushAssistantMarkdown()
        clearThinkingPlaceholder()
        val wasReasoningActive = reasoningPromptActive
        ensureLineStart()
        if (wasReasoningActive) output.writeLine()

        val summary = summarizeToolArgs(event.call.arguments)
        val line = if (summary.isEmpty()) {
            "$TOOL_MARK ${event.call.name}"
        } else {
            "$TOOL_MARK ${event.call.name}  $summary"
        }
        output.writeLine(theme.dim(line))
        markLineWritten()
        assistantPromptActive = false
        reasoningPromptActive = false
        needsAssistantContinuationGap = true
        flushOutput()
    }

    private fun printToolResult(event: AgentEvent.ToolResult) {
        flushAssistantMarkdown()
        ensureLineStart()

        val style: (String) -> String = if (event.isError) theme::error else theme::dim
        val preview = event.output.previewLines()
        if (preview.lines.isEmpty()) {
            if (event.isError) {
                output.writeLine(style("  $TOOL_ERROR_MARK tool error"))
            }
        } else {
            preview.lines.forEach { line ->
                output.writeLine(style("  $line"))
            }
            if (preview.truncated) {
                output.writeLine(style("  ..."))
            }
        }

        markLineWritten()
        assistantPromptActive = false
        reasoningPromptActive = false
        needsAssistantContinuationGap = true
        flushOutput()
    }

    private fun ensureAssistantPrompt() {
        if (assistantPromptActive) return

        clearThinkingPlaceholder()
        val wasReasoningActive = reasoningPromptActive
        ensureLineStart()
        if (wasReasoningActive) output.writeLine()
        if (needsAssistantContinuationGap) output.writeLine()
        if (!assistantPromptPrinted) {
            output.write("${theme.assistantMark()} ")
            contentStarted = true
            endedWithNewLine = false
            assistantPromptPrinted = true
        }
        needsAssistantContinuationGap = false
        assistantPromptActive = true
        reasoningPromptActive = false
    }

    private fun ensureReasoningPrompt() {
        if (reasoningPromptActive) return

        ensureLineStart()
        if (needsAssistantContinuationGap) output.writeLine()
//        output.write(theme.dim("[reasoning] "))
        contentStarted = true
        endedWithNewLine = false
        needsAssistantContinuationGap = false
        assistantPromptActive = false
        reasoningPromptActive = true
    }

    private fun ensureThinkingPlaceholder() {
        if (thinkingPlaceholderShown || contentStarted) return
        output.write(theme.dim("…"))
        thinkingPlaceholderShown = true
        contentStarted = true
        endedWithNewLine = false
        flushOutput()
    }

    private fun clearThinkingPlaceholder() {
        if (!thinkingPlaceholderShown) return
        // Move to a new line so the assistant answer / tool output does not trail the marker.
        if (!endedWithNewLine) {
            output.writeLine()
            endedWithNewLine = true
        }
        thinkingPlaceholderShown = false
    }

    private fun ensureLineStart() {
        if (contentStarted && !endedWithNewLine) output.writeLine()
    }

    private fun markContent(text: String) {
        contentStarted = true
        if (text.isNotEmpty()) endedWithNewLine = text.endsWith("\n")
    }

    private fun flushAssistantMarkdown() {
        val rendered = markdown.finish()
        if (rendered.isNotEmpty()) {
            markContent(rendered)
            output.write(rendered)
            flushStreamingOutputIfNeeded(rendered)
        }
    }

    private fun flushStreamingOutputIfNeeded(rendered: String) {
        if (rendered.isNotEmpty()) {
            unflushedStreamingChars += rendered.length
        } else if (!hasFlushedStreamingContent && unflushedStreamingChars == 0) {
            return
        }

        val shouldFlush =
            !hasFlushedStreamingContent ||
                rendered.contains('\n') ||
                unflushedStreamingChars >= STREAMING_FLUSH_CHARS ||
                lastStreamingFlush.elapsedNow() >= STREAMING_FLUSH_INTERVAL
        if (shouldFlush) flushOutput()
    }

    private fun flushOutput() {
        output.flush()
        hasFlushedStreamingContent = true
        unflushedStreamingChars = 0
        lastStreamingFlush = TimeSource.Monotonic.markNow()
    }

    private fun markLineWritten() {
        contentStarted = true
        endedWithNewLine = true
    }

    private fun summarizeToolArgs(arguments: String): String {
        val command = extractJsonString(arguments, "command")
        if (command != null) return truncateSummary(command.compactForLine())

        val path = extractJsonString(arguments, "path")
        if (path != null) {
            val fileName = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
            val offset = extractJsonInt(arguments, "offset")
            val limit = extractJsonInt(arguments, "limit")
            val range = when {
                offset != null && limit != null -> "  $offset-${offset + limit - 1}"
                offset != null -> "  from $offset"
                limit != null -> "  1-$limit"
                else -> ""
            }
            return truncateSummary("$fileName$range")
        }

        val first = extractFirstJsonStringValue(arguments) ?: return ""
        return truncateSummary(first.compactForLine())
    }

    private fun truncateSummary(text: String): String {
        val maxWidth = (PANEL_WIDTH - TOOL_SUMMARY_PREFIX_RESERVE).coerceAtLeast(16)
        if (TextUtil.visibleWidth(text) <= maxWidth) return text
        return TextUtil.truncateVisible(text, (maxWidth - 3).coerceAtLeast(1)) + "..."
    }

    private fun String.compactForLine(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString(" ") { it.trim() }
            .trim()

    private fun String.previewLines(): ToolResultPreview {
        val normalized = replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd('\n')
        if (normalized.isEmpty()) return ToolResultPreview(emptyList(), truncated = false)

        val lines = normalized.split('\n')
        return ToolResultPreview(
            lines = lines.take(TOOL_RESULT_PREVIEW_LINES),
            truncated = lines.size > TOOL_RESULT_PREVIEW_LINES,
        )
    }

    private data class ToolResultPreview(
        val lines: List<String>,
        val truncated: Boolean,
    )

    private companion object {
        const val TOOL_MARK = "▸"
        const val TOOL_ERROR_MARK = "✗"
        const val TOOL_RESULT_PREVIEW_LINES = 5
        const val TOOL_SUMMARY_PREFIX_RESERVE = 20
        const val STREAMING_FLUSH_CHARS = 128
        val STREAMING_FLUSH_INTERVAL = 16.milliseconds
        val JSON_STRING_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val JSON_NUMBER_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

        fun extractJsonString(raw: String, key: String): String? {
            for (match in JSON_STRING_FIELD_REGEX.findAll(raw)) {
                if (match.groupValues[1] == key) return unescapeJsonString(match.groupValues[2])
            }
            return null
        }

        fun extractJsonInt(raw: String, key: String): Int? {
            for (match in JSON_NUMBER_FIELD_REGEX.findAll(raw)) {
                if (match.groupValues[1] == key) return match.groupValues[2].toIntOrNull()
            }
            return extractJsonString(raw, key)?.toIntOrNull()
        }

        fun extractFirstJsonStringValue(raw: String): String? =
            JSON_STRING_FIELD_REGEX.find(raw)?.let { unescapeJsonString(it.groupValues[2]) }

        fun unescapeJsonString(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '\\' && index + 1 < value.length) {
                    when (val next = value[index + 1]) {
                        '"', '\\', '/' -> append(next)
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'b' -> append('\b')
                        'f' -> append('\u000c')
                        'u' -> {
                            if (index + 5 < value.length) {
                                val hex = value.substring(index + 2, index + 6)
                                append(hex.toIntOrNull(16)?.toChar() ?: next)
                                index += 4
                            } else {
                                append(next)
                            }
                        }
                        else -> append(next)
                    }
                    index += 2
                } else {
                    append(char)
                    index += 1
                }
            }
        }
    }
}
