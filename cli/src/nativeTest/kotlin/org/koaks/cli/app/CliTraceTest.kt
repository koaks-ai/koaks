package org.koaks.cli.app

import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CliTraceTest {
    @Test
    fun recordsToolResultRenderingWithoutContent() {
        val writer = MemoryTraceWriter()
        var now = 0L
        val trace = CliTrace(writer) { now }

        trace.turnStarted(inputLength = 12)
        val call = AgentEvent.ToolCallRequested(ToolCall("call 1", "PowerShell", "secret args"))
        trace.eventReceived(call)
        trace.eventRendered(call)
        now = 25
        val result = AgentEvent.ToolResult("call 1", "secret output", isError = false)
        trace.eventReceived(result)
        trace.renderStage(result, "stdout.write.start", renderedChars = 13)
        trace.markdownFallback(
            reason = "exception",
            state = "inline_code",
            pendingChars = 7,
            errorType = "SensitiveParserException",
        )
        now = 32
        trace.eventRendered(result)
        trace.close()

        val content = writer.content()
        assertContains(content, "event=tool.call")
        assertContains(content, "id=call_1 name=PowerShell")
        assertContains(content, "event=tool.result ")
        assertContains(content, "tool_ms=25")
        assertContains(content, "event=agent.event.received turn=1 index=2 type=tool_result output_chars=13")
        assertContains(content, "event=agent.render.stage turn=1 index=2 type=tool_result stage=stdout.write.start rendered_chars=13")
        assertContains(
            content,
            "event=markdown.fallback turn=1 index=2 reason=exception state=inline_code pending_chars=7 error=SensitiveParserException",
        )
        assertContains(content, "event=agent.event.rendered turn=1 index=2 type=tool_result render_ms=7")
        assertContains(content, "event=tool.result.rendered")
        assertFalse(content.contains("secret args"))
        assertFalse(content.contains("secret output"))
    }

    private class MemoryTraceWriter : TraceWriter {
        private val content = StringBuilder()

        override fun write(line: String) {
            content.append(line)
        }

        override fun close() = Unit

        fun content(): String = content.toString()
    }
}
