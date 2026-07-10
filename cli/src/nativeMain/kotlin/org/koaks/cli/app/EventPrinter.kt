package org.koaks.cli.app

import org.koaks.cli.tui.Output
import org.koaks.cli.tui.Theme
import org.koaks.framework.loop.AgentEvent

internal class EventPrinter(
    private val showReasoning: Boolean,
    private val output: Output,
    private val theme: Theme,
) {
    private val toolNames = mutableMapOf<String, String>()
    private var assistantPromptActive = false
    private var assistantPromptPrinted = false
    private var reasoningPromptActive = false
    private var needsAssistantContinuationGap = false
    private var contentStarted = false
    private var endedWithNewLine = false

    fun print(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                ensureAssistantPrompt()
                markContent(event.text)
                output.write(event.text)
                output.flush()
            }

            is AgentEvent.ReasoningDelta -> {
                if (showReasoning) {
                    ensureReasoningPrompt()
                    markContent(event.text)
                    output.write(theme.dim(event.text))
                    output.flush()
                }
            }

            is AgentEvent.Completed -> {
                if (!endedWithNewLine) output.writeLine()
            }

            is AgentEvent.Terminated -> {
                if (!endedWithNewLine) output.writeLine()
                output.writeLine(theme.warn("[terminated] ${event.reason}"))
            }

            is AgentEvent.Failed -> {
                ensureLineStart()
                output.writeLine(theme.error("[error] ${event.error.message}"))
            }

            is AgentEvent.ToolCallRequested -> printToolCall(event)
            is AgentEvent.ToolResult -> printToolResult(event)
            is AgentEvent.StepCompleted -> Unit
        }
    }

    private fun printToolCall(event: AgentEvent.ToolCallRequested) {
        toolNames[event.call.id] = event.call.name
        ensureLineStart()

        val arguments = event.call.arguments.compactForLine()
        val suffix = if (arguments.isBlank()) "" else " $arguments"
        output.writeLine(theme.dim("[tool call] ${event.call.name}$suffix"))
        markLineWritten()
        assistantPromptActive = false
        reasoningPromptActive = false
        needsAssistantContinuationGap = true
    }

    private fun printToolResult(event: AgentEvent.ToolResult) {
        ensureLineStart()

        val toolName = toolNames[event.callId] ?: event.callId
        val heading = if (event.isError) "[tool error] $toolName" else "[tool result] $toolName"
        output.writeLine(if (event.isError) theme.error(heading) else theme.dim(heading))

        val preview = event.output.previewLines()
        preview.lines.forEach { line ->
            val content = "  $line"
            output.writeLine(if (event.isError) theme.error(content) else theme.dim(content))
        }
        if (preview.truncated) {
            val ellipsis = "  ..."
            output.writeLine(if (event.isError) theme.error(ellipsis) else theme.dim(ellipsis))
        }
        markLineWritten()
        assistantPromptActive = false
        reasoningPromptActive = false
        needsAssistantContinuationGap = true
    }

    private fun ensureAssistantPrompt() {
        if (assistantPromptActive) return

        ensureLineStart()
        if (needsAssistantContinuationGap) output.writeLine()
        if (!assistantPromptPrinted) {
            output.write("${theme.prompt("koaks")} ")
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

    private fun ensureLineStart() {
        if (contentStarted && !endedWithNewLine) output.writeLine()
    }

    private fun markContent(text: String) {
        contentStarted = true
        if (text.isNotEmpty()) endedWithNewLine = text.endsWith("\n")
    }

    private fun markLineWritten() {
        contentStarted = true
        endedWithNewLine = true
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
        const val TOOL_RESULT_PREVIEW_LINES = 5
    }
}
