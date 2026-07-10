package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.LineSuggestion
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.Theme
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertContains(output.content, Ansi.cursorUp(suggestions.size + 1))
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

    @Test
    fun fixedOutputScrollRegionStopsRightAboveInputBox() {
        val output = RecordingOutput()
        val layout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 6,
        )

        InputBox.enterFixedLayout(output, layout)

        assertEquals(layout.inputTopRow - 1, layout.outputBottomRow)
        assertContains(output.content, Ansi.scrollRegion(1, layout.inputTopRow - 1))
    }

    @Test
    fun resizingFixedLayoutUpdatesScrollRegionAndClearsInputAreas() {
        val output = RecordingOutput()
        val oldLayout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 6,
        )
        val newLayout = TerminalLayout.of(
            rows = 30,
            columns = 100,
            fixedInput = true,
            commandMenuRows = 6,
        )

        InputBox.resizeFixedLayout(output, oldLayout, newLayout, previousMenuRows = 1)

        assertContains(output.content, Ansi.scrollRegion(1, newLayout.outputBottomRow))
        assertContains(output.content, "${Ansi.cursor(oldLayout.inputTopRow - 1, 1)}${Ansi.CLEAR_LINE}")
        assertContains(output.content, "${Ansi.cursor(newLayout.inputTopRow - 1, 1)}${Ansi.CLEAR_LINE}")
    }

    @Test
    fun rendersFixedCommandMenuAboveInputBox() {
        val output = RecordingOutput()
        val suggestions = listOf(
            LineSuggestion("/help", "Show help"),
            LineSuggestion("/exit", "Quit"),
        )
        val layout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = suggestions.size,
        )
        val snapshot = LineEditorSnapshot(
            text = "/",
            cursor = 1,
            suggestions = suggestions,
            selectedSuggestionIndex = 0,
            recognizedCommandEnd = null,
        )

        val rows = InputBox.renderFixedEditor(output, layout, Theme(enabled = true), snapshot)

        assertEquals(suggestions.size, rows)
        assertTrue(layout.menuTopRow < layout.inputTopRow)
        assertTrue(output.content.indexOf(Ansi.cursor(layout.menuTopRow, 1)) <
            output.content.indexOf(Ansi.cursor(layout.inputTopRow, 1)))
        assertContains(output.content, "${Ansi.BOLD}${Ansi.BLUE}/help")
    }

    @Test
    fun rendersFilteredFixedCommandMenuAdjacentToInputBox() {
        val output = RecordingOutput()
        val suggestions = listOf(
            LineSuggestion("/exit", "Quit"),
        )
        val layout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 6,
        )
        val snapshot = LineEditorSnapshot(
            text = "/ex",
            cursor = 3,
            suggestions = suggestions,
            selectedSuggestionIndex = 0,
            recognizedCommandEnd = null,
        )

        val rows = InputBox.renderFixedEditor(output, layout, Theme(enabled = true), snapshot)

        assertEquals(suggestions.size, rows)
        val adjacentMenuCursor = Ansi.cursor(layout.inputTopRow - suggestions.size, 1)
        assertContains(output.content, adjacentMenuCursor)
        assertTrue(output.content.lastIndexOf(adjacentMenuCursor) <
            output.content.indexOf("${Ansi.BOLD}${Ansi.BLUE}/exit"))
        assertContains(output.content, "${Ansi.BOLD}${Ansi.BLUE}/exit")
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
