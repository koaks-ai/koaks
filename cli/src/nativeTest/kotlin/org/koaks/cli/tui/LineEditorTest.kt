package org.koaks.cli.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineEditorTest {
    private val suggestions = listOf(
        LineSuggestion("/help", "Show help"),
        LineSuggestion("/status", "Show status"),
        LineSuggestion("/exit", "Quit"),
        LineSuggestion("/quit", "Quit"),
    )

    @Test
    fun slashShowsAllCommandsAndSelectsFirstPrefixMatch() {
        val editor = editor()

        editor.accept(TerminalKey.Text("/"))
        val slashSnapshot = editor.snapshot()
        assertEquals(suggestions, slashSnapshot.suggestions)
        assertEquals(0, slashSnapshot.selectedSuggestionIndex)

        editor.accept(TerminalKey.Text("ex"))
        val prefixSnapshot = editor.snapshot()
        assertEquals(2, prefixSnapshot.selectedSuggestionIndex)
        assertEquals("/exit", prefixSnapshot.suggestions[prefixSnapshot.selectedSuggestionIndex!!].value)
    }

    @Test
    fun tabAcceptsSelectedPrefixMatch() {
        val editor = editor()
        editor.accept(TerminalKey.Text("/ex"))

        editor.accept(TerminalKey.Tab)

        assertEquals("/exit", editor.snapshot().text)
    }

    @Test
    fun enterSubmitsSelectedPrefixMatch() {
        val editor = editor()
        editor.accept(TerminalKey.Text("/ex"))

        val result = editor.accept(TerminalKey.Enter)

        assertEquals(LineEditResult.Submit("/exit"), result)
    }

    @Test
    fun onlyCompleteBuiltinCommandTokenIsRecognized() {
        val editor = editor()
        editor.accept(TerminalKey.Text("/ex"))
        assertNull(editor.snapshot().recognizedCommandEnd)

        editor.accept(TerminalKey.Text("it argument"))
        assertEquals(5, editor.snapshot().recognizedCommandEnd)
    }

    private fun editor(): LineEditor = LineEditor(
        LineReadRequest(
            suggestions = suggestions,
            commandNames = suggestions.mapTo(mutableSetOf()) { it.value },
            onUpdate = {},
        )
    )
}
