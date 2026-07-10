package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.LineSuggestion
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.Theme
import kotlin.test.Test
import kotlin.test.assertContains

class InputBoxTest {
    @Test
    fun rendersRecognizedCommandTokenInBlue() {
        val output = RecordingOutput()
        val snapshot = LineEditorSnapshot(
            text = "/exit now",
            cursor = 9,
            suggestions = emptyList(),
            selectedSuggestionIndex = null,
            recognizedCommandEnd = 5,
        )

        InputBox.renderStaticEditor(output, Theme(enabled = true), snapshot, previousMenuRows = 0)

        assertContains(output.content, "${Ansi.BLUE}/exit${Ansi.RESET} now")
    }

    @Test
    fun rendersPrefixMatchedCommandAsSelectedMenuItem() {
        val output = RecordingOutput()
        val suggestions = listOf(
            LineSuggestion("/help", "Show help"),
            LineSuggestion("/exit", "Quit"),
        )
        val snapshot = LineEditorSnapshot(
            text = "/ex",
            cursor = 3,
            suggestions = suggestions,
            selectedSuggestionIndex = 1,
            recognizedCommandEnd = null,
        )

        val rows = InputBox.renderStaticEditor(output, Theme(enabled = true), snapshot, previousMenuRows = 0)

        assertContains(output.content, "${Ansi.BOLD}${Ansi.BLUE}/exit")
        kotlin.test.assertEquals(suggestions.size, rows)
    }

    @Test
    fun positionsFixedInputCursorAfterWideCharacters() {
        val output = RecordingOutput()
        val layout = TerminalLayout.of(rows = 40, columns = 80, fixedInput = true)
        val snapshot = LineEditorSnapshot(
            text = "为什么",
            cursor = 3,
            suggestions = emptyList(),
            selectedSuggestionIndex = null,
            recognizedCommandEnd = null,
        )

        InputBox.renderFixedEditor(output, layout, Theme(enabled = true), snapshot)

        assertContains(output.content, Ansi.cursor(layout.compactInputRow, 11))
    }
}

private class RecordingOutput : Output {
    private val buffer = StringBuilder()
    val content: String get() = buffer.toString()

    override fun write(text: String) {
        buffer.append(text)
    }

    override fun writeLine(text: String) {
        buffer.append(text).append('\n')
    }

    override fun flush() = Unit
}
