package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme

internal object InputBox {
    fun renderStaticStart(output: Output, theme: Theme, commandMenuRows: Int = 0) {
        if (theme.enabled) output.write(Ansi.BLINKING_BAR_CURSOR)
        output.writeLine()
        repeat(commandMenuRows.coerceAtLeast(0)) { output.writeLine() }
        output.writeLine(theme.inputBorder(inputLine()))
        output.write("${theme.inputSide()} ${theme.inputPrompt()} ")
    }

    fun renderStaticEnd(output: Output, theme: Theme, inputWasEchoed: Boolean) {
        if (!inputWasEchoed) output.writeLine()
        output.writeLine(theme.inputBorder(inputLine()))
        if (theme.enabled) output.write(Ansi.RESET_CURSOR_STYLE)
    }

    fun renderStaticEditor(
        output: Output,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        previousMenuRows: Int,
    ): Int {
        val menuLines = commandMenuLines(snapshot, theme, PANEL_WIDTH - 2, snapshot.suggestions.size)
        val rowsToClear = maxOf(previousMenuRows, menuLines.size)
        output.write("\r${Ansi.CLEAR_LINE}${inputContent(snapshot, theme)}")
        if (rowsToClear > 0) {
            output.write(Ansi.cursorUp(rowsToClear + 1))
            val firstMenuLineIndex = rowsToClear - menuLines.size
            repeat(rowsToClear) { index ->
                output.write("\r${Ansi.CLEAR_LINE}")
                if (index >= firstMenuLineIndex) output.write(menuLines[index - firstMenuLineIndex])
                if (index < rowsToClear - 1) output.write(Ansi.cursorDown(1))
            }
            output.write(Ansi.cursorDown(2))
        }
        output.write(Ansi.cursorColumn(inputCursorColumn(snapshot)))
        return menuLines.size
    }

    fun renderStaticInteractiveEnd(
        output: Output,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        menuRows: Int,
    ) {
        output.write("\r${Ansi.CLEAR_LINE}${inputContent(snapshot, theme)}")
        if (menuRows > 0) {
            output.write(Ansi.cursorUp(menuRows + 1))
            repeat(menuRows) { index ->
                output.write("\r${Ansi.CLEAR_LINE}")
                if (index < menuRows - 1) output.write(Ansi.cursorDown(1))
            }
            output.write(Ansi.cursorDown(2))
        }
        output.write("\r${Ansi.CLEAR_LINE}")
        output.writeLine(inputContent(snapshot, theme))
        output.writeLine(theme.inputBorder(inputLine()))
        if (theme.enabled) output.write(Ansi.RESET_CURSOR_STYLE)
    }

    fun enterFixedLayout(output: Output, layout: TerminalLayout) {
        output.write("${Ansi.BLINKING_BAR_CURSOR}${Ansi.CLEAR_SCREEN}${Ansi.HOME}${Ansi.scrollRegion(1, layout.outputBottomRow)}")
    }

    fun leaveFixedLayout(output: Output, layout: TerminalLayout) {
        output.write("${Ansi.RESET_SCROLL_REGION}${Ansi.cursor(layout.rows, 1)}${Ansi.RESET_CURSOR_STYLE}${Ansi.RESET}")
    }

    fun resizeFixedLayout(
        output: Output,
        oldLayout: TerminalLayout,
        newLayout: TerminalLayout,
        previousMenuRows: Int = 0,
    ) {
        output.write(Ansi.scrollRegion(1, newLayout.outputBottomRow))
        clearReservedInputArea(output, oldLayout, previousMenuRows)
        clearReservedInputArea(output, newLayout, previousMenuRows)
    }

    fun renderFixed(output: Output, layout: TerminalLayout, theme: Theme) {
        output.write(Ansi.SAVE_CURSOR)
        drawCompactFixedInputBox(output, layout, theme)
        output.write(Ansi.cursor(layout.compactInputRow, 1))
        output.write("${theme.inputSide()} ${theme.inputPrompt()} ")
    }

    fun renderFixedEditor(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        previousMenuRows: Int = 0,
    ): Int {
        val menuLines = commandMenuLines(snapshot, theme, layout.columns - 2, layout.commandMenuRows)
        val menuRowsToClear = maxOf(previousMenuRows, menuLines.size)
        clearReservedInputArea(output, layout, menuRowsToClear)
        if (menuLines.isNotEmpty()) {
            drawExpandedFixedInputBox(output, layout, theme, snapshot, menuLines)
            output.write(Ansi.cursor(layout.inputRow, inputCursorColumn(snapshot)))
        } else {
            drawCompactFixedInputBox(output, layout, theme, snapshot)
            output.write(Ansi.cursor(layout.compactInputRow, inputCursorColumn(snapshot)))
        }
        return menuLines.size
    }

    fun restoreOutputCursor(output: Output, layout: TerminalLayout, theme: Theme, menuRows: Int = 0) {
        output.write(Ansi.RESTORE_CURSOR)
        output.write(Ansi.SAVE_CURSOR)
        clearReservedInputArea(output, layout, menuRows)
        drawCompactFixedInputBox(output, layout, theme)
        output.write(Ansi.RESTORE_CURSOR)
    }

    private fun clearReservedInputArea(output: Output, layout: TerminalLayout, menuRows: Int) {
        val topRow = (layout.inputTopRow - menuRows).coerceAtLeast(1)
        for (row in topRow..layout.inputBottomRow) {
            output.write("${Ansi.cursor(row, 1)}${Ansi.CLEAR_LINE}")
        }
    }

    private fun drawCompactFixedInputBox(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot? = null,
    ) {
        val line = fixedInputLine(layout)
        output.write("${Ansi.cursor(layout.compactInputTopRow, 1)}${Ansi.CLEAR_LINE}${theme.inputBorder(line)}")
        output.write("${Ansi.cursor(layout.compactInputRow, 1)}${Ansi.CLEAR_LINE}")
        if (snapshot == null) {
            output.write(theme.inputSide())
        } else {
            output.write(inputContent(snapshot, theme))
        }
        output.write("${Ansi.cursor(layout.inputBottomRow, 1)}${Ansi.CLEAR_LINE}${theme.inputBorder(line)}")
    }

    private fun drawExpandedFixedInputBox(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        menuLines: List<String>,
    ) {
        val line = fixedInputLine(layout)
        val menuTopRow = layout.inputTopRow - menuLines.size
        menuLines.forEachIndexed { index, menuLine ->
            output.write(Ansi.cursor(menuTopRow + index, 1))
            output.write("${theme.inputSide()} $menuLine")
        }
        output.write("${Ansi.cursor(layout.inputTopRow, 1)}${theme.inputBorder(line)}")
        output.write("${Ansi.cursor(layout.inputRow, 1)}${inputContent(snapshot, theme)}")
        output.write("${Ansi.cursor(layout.inputBottomRow, 1)}${theme.inputBorder(line)}")
    }

    private fun inputContent(snapshot: LineEditorSnapshot, theme: Theme): String = buildString {
        append(theme.inputSide())
        append(' ')
        append(theme.inputPrompt())
        append(' ')
        val commandEnd = snapshot.recognizedCommandEnd
        if (commandEnd == null) {
            append(snapshot.text)
        } else {
            append(theme.command(snapshot.text.substring(0, commandEnd)))
            append(snapshot.text.substring(commandEnd))
        }
    }

    private fun commandMenuLines(
        snapshot: LineEditorSnapshot,
        theme: Theme,
        width: Int,
        capacity: Int,
    ): List<String> {
        if (!snapshot.menuVisible || capacity <= 0) return emptyList()
        val suggestions = snapshot.suggestions
        val selected = snapshot.selectedSuggestionIndex
        val start = when {
            suggestions.size <= capacity -> 0
            selected == null -> 0
            selected < capacity -> 0
            else -> (selected - capacity + 1).coerceAtMost(suggestions.size - capacity)
        }
        val commandWidth = suggestions.maxOfOrNull { it.value.length } ?: 0
        return suggestions.drop(start).take(capacity).mapIndexed { offset, suggestion ->
            val index = start + offset
            val marker = if (index == selected) theme.commandMenuSelection("›") else " "
            val command = if (index == selected) {
                theme.commandMenuSelection(suggestion.value.padEnd(commandWidth))
            } else {
                theme.command(suggestion.value.padEnd(commandWidth))
            }
            val description = theme.dim(suggestion.description)
            TextUtil.truncateVisible("$marker $command  $description", width)
        }
    }

    private fun inputCursorColumn(snapshot: LineEditorSnapshot): Int =
        INPUT_TEXT_COLUMN + TextUtil.visibleWidth(snapshot.text.substring(0, snapshot.cursor))

    private fun inputLine(): String =
        TextUtil.rule('─', PANEL_WIDTH)

    private fun fixedInputLine(layout: TerminalLayout): String =
        TextUtil.rule('─', (layout.columns - 1).coerceAtLeast(1))

    private const val INPUT_TEXT_COLUMN = 5
}
