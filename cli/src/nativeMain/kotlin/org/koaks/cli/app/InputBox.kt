package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme

internal object InputBox {
    fun renderStaticStart(output: Output, theme: Theme) {
        output.writeLine()
        output.writeLine(theme.inputBorder(inputLine()))
        output.write("${theme.inputSide()} ${theme.inputPrompt()} ")
    }

    fun renderStaticEnd(output: Output, theme: Theme, inputWasEchoed: Boolean) {
        if (!inputWasEchoed) output.writeLine()
        output.writeLine(theme.inputBorder(inputLine()))
    }

    fun enterFixedLayout(output: Output, layout: TerminalLayout) {
        output.write("${Ansi.CLEAR_SCREEN}${Ansi.HOME}${Ansi.scrollRegion(1, layout.outputBottomRow)}")
    }

    fun leaveFixedLayout(output: Output, layout: TerminalLayout) {
        output.write("${Ansi.RESET_SCROLL_REGION}${Ansi.cursor(layout.rows, 1)}${Ansi.RESET}")
    }

    fun renderFixed(output: Output, layout: TerminalLayout, theme: Theme) {
        output.write(Ansi.SAVE_CURSOR)
        drawFixedInputBox(output, layout, theme)
        output.write(Ansi.cursor(layout.inputRow, 1))
        output.write("${theme.inputSide()} ${theme.inputPrompt()} ")
    }

    fun restoreOutputCursor(output: Output, layout: TerminalLayout, theme: Theme) {
        output.write(Ansi.RESTORE_CURSOR)
        clearFixedInputBox(output, layout, theme)
    }

    private fun clearFixedInputBox(output: Output, layout: TerminalLayout, theme: Theme) {
        output.write(Ansi.SAVE_CURSOR)
        drawFixedInputBox(output, layout, theme)
        output.write(Ansi.RESTORE_CURSOR)
    }

    private fun drawFixedInputBox(output: Output, layout: TerminalLayout, theme: Theme) {
        val line = fixedInputLine(layout)
        output.write("${Ansi.cursor(layout.inputTopRow, 1)}${Ansi.CLEAR_LINE}${theme.inputBorder(line)}")
        output.write("${Ansi.cursor(layout.inputRow, 1)}${Ansi.CLEAR_LINE}${theme.inputSide()}")
        output.write("${Ansi.cursor(layout.inputBottomRow, 1)}${Ansi.CLEAR_LINE}${theme.inputBorder(line)}")
    }

    private fun inputLine(): String =
        TextUtil.rule('─', PANEL_WIDTH)

    private fun fixedInputLine(layout: TerminalLayout): String =
        TextUtil.rule('─', (layout.columns - 1).coerceAtLeast(1))
}
