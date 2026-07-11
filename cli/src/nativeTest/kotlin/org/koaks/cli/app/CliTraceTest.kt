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
        trace.eventReceived(AgentEvent.ToolCallRequested(ToolCall("call 1", "PowerShell", "secret args")))
        now = 25
        val result = AgentEvent.ToolResult("call 1", "secret output", isError = false)
        trace.eventReceived(result)
        trace.eventRendered(result)
        trace.close()

        val content = writer.content()
        assertContains(content, "event=tool.call")
        assertContains(content, "id=call_1 name=PowerShell")
        assertContains(content, "event=tool.result ")
        assertContains(content, "tool_ms=25")
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
