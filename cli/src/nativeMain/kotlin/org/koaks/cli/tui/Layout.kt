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
) {
    val outputBottomRow: Int = (rows - INPUT_BOX_HEIGHT).coerceAtLeast(1)
    val inputTopRow: Int = (rows - INPUT_BOX_HEIGHT + 1).coerceAtLeast(1)
    val inputRow: Int = (rows - 1).coerceAtLeast(1)
    val inputBottomRow: Int = rows.coerceAtLeast(1)

    companion object {
        /** Builds a layout, clamping to the minimum usable rows/columns. */
        fun of(rows: Int, columns: Int, fixedInput: Boolean): TerminalLayout =
            TerminalLayout(
                fixedInput = fixedInput,
                rows = rows.coerceAtLeast(INPUT_BOX_HEIGHT + 3),
                columns = columns.coerceAtLeast(32),
            )
    }
}
