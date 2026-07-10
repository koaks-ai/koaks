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
        val selectedIndex = if (menuVisible()) {
            manuallySelectedIndex ?: request.suggestions.indexOfFirst { suggestion ->
                suggestion.value.startsWith(text, ignoreCase = true)
            }.takeIf { it >= 0 }
        } else {
            null
        }
        val commandEnd = text.indexOfFirst(Char::isWhitespace).let { if (it < 0) text.length else it }
        val command = text.substring(0, commandEnd).normalizeCommandName()
        return LineEditorSnapshot(
            text = text,
            cursor = cursor,
            suggestions = request.suggestions,
            selectedSuggestionIndex = selectedIndex,
            recognizedCommandEnd = commandEnd.takeIf { command in request.commandNames },
        )
    }

    fun accept(key: TerminalKey): LineEditResult {
        when (key) {
            TerminalKey.Enter -> {
                val selected = selectedIndex()
                if (menuVisible() && selected != null) {
                    text = request.suggestions[selected].value
                    cursor = text.length
                }
                return LineEditResult.Submit(text)
            }
            TerminalKey.EndOfInput -> return LineEditResult.EndOfInput
            TerminalKey.Backspace -> deleteBeforeCursor()
            TerminalKey.Delete -> deleteAtCursor()
            TerminalKey.Left -> cursor = previousCharacterIndex(text, cursor)
            TerminalKey.Right -> {
                if (cursor == text.length && menuVisible() && selectedIndex() != null &&
                    request.suggestions[selectedIndex()!!].value.length > text.length
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
        if (!menuVisible() || request.suggestions.isEmpty()) return
        val current = selectedIndex() ?: if (delta > 0) -1 else 0
        manuallySelectedIndex = (current + delta).mod(request.suggestions.size)
    }

    private fun acceptSuggestion() {
        if (!menuVisible()) return
        val index = selectedIndex() ?: return
        text = request.suggestions[index].value
        cursor = text.length
        manuallySelectedIndex = index
    }

    private fun selectedIndex(): Int? = snapshot().selectedSuggestionIndex

    private fun menuVisible(): Boolean =
        text.startsWith("/") && text.none(Char::isWhitespace)
}

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
