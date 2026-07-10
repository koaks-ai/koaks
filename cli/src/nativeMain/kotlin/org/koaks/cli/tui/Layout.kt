package org.koaks.cli.tui

/** Rows reserved for the input box at the bottom of the screen in fixed-input mode. */
internal const val INPUT_BOX_HEIGHT = 3

/** Fallback row count when the terminal size can't be probed. */
internal const val DEFAULT_TERM_ROWS = 30

/**
 * Pure geometry for the fixed-input layout: given the terminal [rows]/[columns] and
 * whether the pinned input box is active, exposes the 1-based row coordinates the
 * renderer draws against. Holds no theme and reads no environment — callers resolve
 * those and pass the numbers in via [of].
 */
internal class TerminalLayout private constructor(
    val fixedInput: Boolean,
    val rows: Int,
    val columns: Int,
    val commandMenuRows: Int,
) {
    private val reservedInputHeight: Int = INPUT_BOX_HEIGHT + commandMenuRows

    val outputBottomRow: Int = (rows - reservedInputHeight).coerceAtLeast(1)
    val reservedInputTopRow: Int = (outputBottomRow + 1).coerceAtMost(rows)
    val inputTopRow: Int = (rows - INPUT_BOX_HEIGHT + 1).coerceAtLeast(1)
    val inputRow: Int = (rows - 1).coerceAtLeast(1)
    val inputBottomRow: Int = rows.coerceAtLeast(1)
    val compactInputTopRow: Int = (rows - INPUT_BOX_HEIGHT + 1).coerceAtLeast(1)
    val compactInputRow: Int = (rows - 1).coerceAtLeast(1)
    val menuTopRow: Int = (inputTopRow - commandMenuRows).coerceAtLeast(1)

    companion object {
        /** Builds a layout, clamping to the minimum usable rows/columns. */
        fun of(
            rows: Int,
            columns: Int,
            fixedInput: Boolean,
            commandMenuRows: Int = 0,
        ): TerminalLayout {
            val safeRows = rows.coerceAtLeast(INPUT_BOX_HEIGHT + 3)
            val availableMenuRows = (safeRows - INPUT_BOX_HEIGHT - 3).coerceAtLeast(0)
            return TerminalLayout(
                fixedInput = fixedInput,
                rows = safeRows,
                columns = columns.coerceAtLeast(32),
                commandMenuRows = commandMenuRows.coerceIn(0, availableMenuRows),
            )
        }
    }
}
