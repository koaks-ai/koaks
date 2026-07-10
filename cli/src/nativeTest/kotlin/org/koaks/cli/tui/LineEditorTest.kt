package org.koaks.cli.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineEditorTest {
    private val suggestions = listOf(
        LineSuggestion("/help", "Show help"),
        LineSuggestion("/status", "Show status"),
        LineSuggestion("/exit", "Quit"),
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
        assertEquals(listOf(LineSuggestion("/exit", "Quit")), prefixSnapshot.suggestions)
        assertEquals(0, prefixSnapshot.selectedSuggestionIndex)
        assertEquals("/exit", prefixSnapshot.suggestions[prefixSnapshot.selectedSuggestionIndex!!].value)
    }

    @Test
    fun filtersUnrelatedCommandsOutOfMenu() {
        val editor = editor()

        editor.accept(TerminalKey.Text("/zz"))

        assertEquals(emptyList(), editor.snapshot().suggestions)
        assertNull(editor.snapshot().selectedSuggestionIndex)
    }

    @Test
    fun ordersBetterCommandMatchesFirst() {
        val editor = editor(
            listOf(
                LineSuggestion("/exit", "Quit"),
                LineSuggestion("/itinerary", "Show itinerary"),
            )
        )

        editor.accept(TerminalKey.Text("/it"))
        val snapshot = editor.snapshot()

        assertEquals("/itinerary", snapshot.suggestions.first().value)
        assertEquals(0, snapshot.selectedSuggestionIndex)
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

    private fun editor(availableSuggestions: List<LineSuggestion> = suggestions): LineEditor = LineEditor(
        LineReadRequest(
            suggestions = availableSuggestions,
            commandNames = availableSuggestions.mapTo(mutableSetOf()) { it.value },
            onUpdate = {},
        )
    )
}
