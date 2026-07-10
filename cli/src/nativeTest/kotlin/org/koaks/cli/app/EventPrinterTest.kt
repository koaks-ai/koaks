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
            ◆ 让我查看一下当前文件夹的内容。
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
            
            ◆ 当前文件夹有 file.txt
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

            ◆ 答案来了。
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

        assertEquals("◆ 只显示答案。", output.content())
    }

    @Test
    fun rendersAssistantMarkdownSubsetWithoutAnsiWhenThemeDisabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("用 **加粗** 和 `关键词`。\n"))
        printer.print(AgentEvent.TextDelta("```kotlin\nval answer = 42\n```\n"))

        assertEquals(
            "◆ 用 加粗 和 关键词。\n" + plainCodeBlock("kotlin", "val answer = 42"),
            output.content(),
        )
    }

    @Test
    fun rendersAssistantMarkdownSubsetAcrossStreamingDeltas() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("这是 **加"))
        printer.print(AgentEvent.TextDelta("粗** 和 `关键"))
        printer.print(AgentEvent.TextDelta("词`。"))

        assertEquals("◆ 这是 加粗 和 关键词。", output.content())
    }

    @Test
    fun rendersCodeBlockAtStartOnItsOwnLine() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("```kotlin\ncode block\n```\n"))

        assertEquals(
            "◆ \n" + plainCodeBlock("kotlin", "code block"),
            output.content(),
        )
    }

    @Test
    fun streamsCodeBlockContentBeforeClosingFenceArrives() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("```kotlin\n"))
        printer.print(AgentEvent.TextDelta("code block\n"))

        assertEquals(
            "◆ \n" + plainCodeBlockStart("kotlin") + plainCodeLine("code block"),
            output.content(),
        )

        printer.print(AgentEvent.TextDelta("```\n"))

        assertEquals(
            "◆ \n" + plainCodeBlock("kotlin", "code block"),
            output.content(),
        )
    }

    @Test
    fun rendersAssistantMarkdownSubsetWithAnsiWhenThemeEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        printer.print(AgentEvent.TextDelta("**加粗** `关键词`\n```kotlin\nval answer = 42\n```\n"))

        val content = output.content()
        assertContains(content, "${Ansi.BOLD}加粗${Ansi.RESET}")
        assertContains(content, "${Ansi.BLUE}关键词${Ansi.RESET}")
        assertContains(content, "${Ansi.BOLD}${Ansi.CODE_LANGUAGE}kotlin${Ansi.RESET}")
        assertContains(content, "${Ansi.BOLD}${Ansi.CODE_KEYWORD}val${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_NUMBER}42${Ansi.RESET}")
    }

    @Test
    fun rendersAdditionalCodeLanguagesWithAnsiWhenThemeEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        listOf(
            "c" to "int main() { return 0; }\n",
            "cpp" to "class Box { public: int x; }\n",
            "java" to "public class Main {}\n",
            "python3" to "def greet(): # hello\n",
            "rust" to "fn main() { let x = 1; }\n",
            "js" to "const x = 1 // hello\n",
            "nodejs" to "function run() { return 1 }\n",
            "html" to "<div class=\"x\">hi</div>\n",
            "xml" to "<node id=\"1\" />\n",
        ).forEach { (language, code) ->
            printer.print(AgentEvent.TextDelta("```$language\n$code```\n"))
        }

        val content = output.content()
        listOf("c", "cpp", "java", "python3", "rust", "js", "nodejs", "html", "xml").forEach { language ->
            assertContains(content, "${Ansi.BOLD}${Ansi.CODE_LANGUAGE}$language${Ansi.RESET}")
        }
        listOf("int", "class", "public", "def", "fn", "const", "function", "div", "node").forEach { keyword ->
            assertContains(content, "${Ansi.BOLD}${Ansi.CODE_KEYWORD}$keyword${Ansi.RESET}")
        }
        assertContains(content, "${Ansi.CODE_COMMENT}# hello${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_COMMENT}// hello${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_STRING}\"x\"${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_STRING}\"1\"${Ansi.RESET}")
    }

    @Test
    fun wrapsLongCodeLinesInsteadOfTruncating() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))
        val longLine = "const message = \"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-tail\";"

        printer.print(AgentEvent.TextDelta("```js\n$longLine\n```\n"))

        assertEquals(
            "◆ \n" + plainCodeBlock(
                "js",
                "const message = \"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
                "56789-tail\";",
            ),
            output.content(),
        )
    }

    @Test
    fun separatesReasoningFromToolCallWhenEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = true, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ReasoningDelta("先查一下。"))
        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls"}""")))

        assertEquals(
            """
            先查一下。

            [tool call] Bash {"command":"ls"}
            
            """.trimIndent(),
            output.content(),
        )
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

private fun plainCodeBlock(language: String, vararg lines: String): String =
    buildString {
        append(plainCodeBlockStart(language))
        lines.forEach { append(plainCodeLine(it)) }
        append(plainCodeBlockEnd())
    }

private fun plainCodeBlockStart(language: String): String {
    val remaining = PANEL_WIDTH - "┌─ ".length - language.length - "┐".length
    return "┌─ $language${"─".repeat(remaining)}┐\n"
}

private fun plainCodeLine(line: String): String =
    "│ ${line.padEnd(PANEL_WIDTH - 4)} │\n"

private fun plainCodeBlockEnd(): String =
    "└${"─".repeat(PANEL_WIDTH - 2)}┘\n"
