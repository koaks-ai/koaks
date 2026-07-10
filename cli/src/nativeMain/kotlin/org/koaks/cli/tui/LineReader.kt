package org.koaks.cli.tui

internal interface LineReader {
    fun readLine(): String?

    fun readLine(request: LineReadRequest): String? = readLine()
}

internal object StdinLineReader : LineReader {
    override fun readLine(): String? = readlnOrNull()

    override fun readLine(request: LineReadRequest): String? {
        if (!NativeTerminalInput.enterRawMode()) return readLine()

        try {
            val editor = LineEditor(request)
            request.onUpdate(editor.snapshot())

            while (true) {
                when (val result = editor.accept(NativeTerminalInput.readKey())) {
                    LineEditResult.Continue -> request.onUpdate(editor.snapshot())
                    is LineEditResult.Submit -> {
                        request.onUpdate(editor.snapshot())
                        return result.text
                    }
                    LineEditResult.EndOfInput -> return null
                }
            }
        } finally {
            NativeTerminalInput.leaveRawMode()
        }
    }
}

internal data class LineSuggestion(
    val value: String,
    val description: String,
)

internal data class LineReadRequest(
    val suggestions: List<LineSuggestion>,
    val commandNames: Set<String>,
    val onUpdate: (LineEditorSnapshot) -> Unit,
)

internal data class LineEditorSnapshot(
    val text: String,
    val cursor: Int,
    val suggestions: List<LineSuggestion>,
    val selectedSuggestionIndex: Int?,
    val recognizedCommandEnd: Int?,
) {
    val menuVisible: Boolean
        get() = text.startsWith("/") && text.none(Char::isWhitespace)
}

internal sealed interface LineEditResult {
    object Continue : LineEditResult
    data class Submit(val text: String) : LineEditResult
    object EndOfInput : LineEditResult
}

internal class LineEditor(private val request: LineReadRequest) {
    private var text: String = ""
    private var cursor: Int = 0
    private var manuallySelectedIndex: Int? = null

    fun snapshot(): LineEditorSnapshot {
        val suggestions = currentSuggestions()
        val selectedIndex = if (menuVisible()) {
            manuallySelectedIndex?.coerceAtMost(suggestions.lastIndex)
                ?: suggestions.indices.firstOrNull()
        } else {
            null
        }
        val commandEnd = text.indexOfFirst(Char::isWhitespace).let { if (it < 0) text.length else it }
        val command = text.substring(0, commandEnd).normalizeCommandName()
        return LineEditorSnapshot(
            text = text,
            cursor = cursor,
            suggestions = suggestions,
            selectedSuggestionIndex = selectedIndex,
            recognizedCommandEnd = commandEnd.takeIf { command in request.commandNames },
        )
    }

    fun accept(key: TerminalKey): LineEditResult {
        when (key) {
            TerminalKey.Enter -> {
                val suggestion = selectedSuggestion()
                if (menuVisible() && suggestion != null) {
                    text = suggestion.value
                    cursor = text.length
                }
                return LineEditResult.Submit(text)
            }
            TerminalKey.EndOfInput -> return LineEditResult.EndOfInput
            TerminalKey.Backspace -> deleteBeforeCursor()
            TerminalKey.Delete -> deleteAtCursor()
            TerminalKey.Left -> cursor = previousCharacterIndex(text, cursor)
            TerminalKey.Right -> {
                val suggestion = selectedSuggestion()
                if (cursor == text.length && menuVisible() && suggestion != null &&
                    suggestion.value.length > text.length
                ) {
                    acceptSuggestion()
                } else {
                    cursor = nextCharacterIndex(text, cursor)
                }
            }
            TerminalKey.Home -> cursor = 0
            TerminalKey.End -> cursor = text.length
            TerminalKey.Up -> moveSelection(-1)
            TerminalKey.Down -> moveSelection(1)
            TerminalKey.Tab -> acceptSuggestion()
            TerminalKey.Escape -> Unit
            is TerminalKey.Text -> insert(key.value)
        }
        return LineEditResult.Continue
    }

    private fun insert(value: String) {
        text = text.substring(0, cursor) + value + text.substring(cursor)
        cursor += value.length
        manuallySelectedIndex = null
    }

    private fun deleteBeforeCursor() {
        if (cursor == 0) return
        val start = previousCharacterIndex(text, cursor)
        text = text.removeRange(start, cursor)
        cursor = start
        manuallySelectedIndex = null
    }

    private fun deleteAtCursor() {
        if (cursor == text.length) return
        text = text.removeRange(cursor, nextCharacterIndex(text, cursor))
        manuallySelectedIndex = null
    }

    private fun moveSelection(delta: Int) {
        val suggestions = currentSuggestions()
        if (!menuVisible() || suggestions.isEmpty()) return
        val current = selectedIndex() ?: if (delta > 0) -1 else 0
        manuallySelectedIndex = (current + delta).mod(suggestions.size)
    }

    private fun acceptSuggestion() {
        if (!menuVisible()) return
        val suggestion = selectedSuggestion() ?: return
        text = suggestion.value
        cursor = text.length
        manuallySelectedIndex = selectedIndex()
    }

    private fun selectedIndex(): Int? = snapshot().selectedSuggestionIndex

    private fun selectedSuggestion(): LineSuggestion? {
        val suggestions = currentSuggestions()
        val index = selectedIndex() ?: return null
        return suggestions.getOrNull(index)
    }

    private fun currentSuggestions(): List<LineSuggestion> {
        if (!menuVisible()) return emptyList()
        if (text == "/") return request.suggestions

        val query = text.lowercase()
        val bareQuery = query.removePrefix("/")
        return request.suggestions.mapIndexedNotNull { index, suggestion ->
            val value = suggestion.value.lowercase()
            val bareValue = value.removePrefix("/")
            val score = when {
                value == query -> 0
                value.startsWith(query) -> 1
                bareValue == bareQuery -> 2
                bareValue.startsWith(bareQuery) -> 3
                value.contains(query) -> 4
                bareValue.contains(bareQuery) -> 5
                else -> return@mapIndexedNotNull null
            }
            ScoredSuggestion(score, suggestion.value.length, index, suggestion)
        }.sortedWith(
            compareBy<ScoredSuggestion> { it.score }
                .thenBy { it.length }
                .thenBy { it.index }
        ).map { it.suggestion }
    }

    private fun menuVisible(): Boolean =
        text.startsWith("/") && text.none(Char::isWhitespace)
}

private data class ScoredSuggestion(
    val score: Int,
    val length: Int,
    val index: Int,
    val suggestion: LineSuggestion,
)

internal sealed interface TerminalKey {
    data class Text(val value: String) : TerminalKey
    object Enter : TerminalKey
    object Backspace : TerminalKey
    object Delete : TerminalKey
    object Left : TerminalKey
    object Right : TerminalKey
    object Up : TerminalKey
    object Down : TerminalKey
    object Home : TerminalKey
    object End : TerminalKey
    object Tab : TerminalKey
    object Escape : TerminalKey
    object EndOfInput : TerminalKey
}

internal expect object NativeTerminalInput {
    fun enterRawMode(): Boolean
    fun leaveRawMode()
    fun readKey(): TerminalKey
}

private fun String.normalizeCommandName(): String =
    if (startsWith("/")) lowercase() else this

private fun previousCharacterIndex(text: String, cursor: Int): Int {
    if (cursor <= 0) return 0
    val previous = cursor - 1
    return if (previous > 0 && text[previous].isLowSurrogate() && text[previous - 1].isHighSurrogate()) {
        previous - 1
    } else {
        previous
    }
}

private fun nextCharacterIndex(text: String, cursor: Int): Int {
    if (cursor >= text.length) return text.length
    return if (cursor + 1 < text.length && text[cursor].isHighSurrogate() && text[cursor + 1].isLowSurrogate()) {
        cursor + 2
    } else {
        cursor + 1
    }
}
