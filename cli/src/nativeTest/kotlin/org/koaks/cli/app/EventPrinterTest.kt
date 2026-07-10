package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.Theme
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.model.ToolCall
import kotlin.test.assertContains
import kotlin.test.Test
import kotlin.test.assertEquals

class EventPrinterTest {
    @Test
    fun continuesAssistantTextAfterToolWithoutRepeatingPrompt() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("让我查看一下当前文件夹的内容。\n"))
        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls -la"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))
        printer.print(AgentEvent.TextDelta("当前文件夹包含 file.txt。"))

        assertEquals(
            """
            [koaks] 让我查看一下当前文件夹的内容。
            [tool call] Bash {"command":"ls -la"}
            [tool result] Bash
              file.txt
            
            当前文件夹包含 file.txt。
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun printsAssistantPromptOnlyWhenTextStarts() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))
        printer.print(AgentEvent.TextDelta("当前文件夹有 file.txt"))

        assertEquals(
            """
            [tool call] Bash {"command":"ls"}
            [tool result] Bash
              file.txt
            
            [koaks] 当前文件夹有 file.txt
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun printsReasoningSeparatelyWhenEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = true, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ReasoningDelta("先分析一下"))
        printer.print(AgentEvent.ReasoningDelta("问题。"))
        printer.print(AgentEvent.TextDelta("答案来了。"))

        assertEquals(
            """
            先分析一下问题。
            [koaks] 答案来了。
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun dropsReasoningWhenDisabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ReasoningDelta("隐藏思考"))
        printer.print(AgentEvent.TextDelta("只显示答案。"))

        assertEquals("[koaks] 只显示答案。", output.content())
    }

    @Test
    fun printsToolCallAndTruncatedResultPreview() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall(
                    id = "call-1",
                    name = "Bash",
                    arguments = """{"command":"ls"}""",
                )
            )
        )
        printer.print(
            AgentEvent.ToolResult(
                callId = "call-1",
                output = (1..6).joinToString("\n") { "line-$it" },
                isError = false,
            )
        )

        assertEquals(
            """
            [tool call] Bash {"command":"ls"}
            [tool result] Bash
              line-1
              line-2
              line-3
              line-4
              line-5
              ...
            
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun dimsToolResultNameWithHeading() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))

        assertContains(output.content(), "${Ansi.DIM}[tool result] Bash${Ansi.RESET}")
    }

    @Test
    fun printsFullToolResultWhenAtMostFiveLines() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Read", """{"path":"file.kt"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "one\ntwo\nthree\nfour\nfive", isError = false))

        assertEquals(
            """
            [tool call] Read {"path":"file.kt"}
            [tool result] Read
              one
              two
              three
              four
              five
            
            """.trimIndent(),
            output.content(),
        )
    }
}

private class BufferOutput : Output {
    private val builder = StringBuilder()

    override fun write(text: String) {
        builder.append(text)
    }

    override fun writeLine(text: String) {
        builder.append(text).append('\n')
    }

    override fun flush() = Unit

    fun content(): String = builder.toString()
}
