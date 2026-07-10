package org.koaks.cli.tui

/**
 * Raw ANSI/VT100 escape sequences. Pure constants and builders — no I/O, no color
 * policy (that lives in [Theme], which decides whether to emit them at all).
 *
 * Sequences are built from the CSI code point (ESC, char 27, followed by an open
 * bracket) so the source file stays free of invisible control characters.
 */
internal object Ansi {
    /** Control Sequence Introducer: ESC (char 27) followed by an open bracket. */
    private val CSI: String = "${Char(27)}["

    val RESET = "${CSI}0m"
    val BOLD = "${CSI}1m"
    val DIM = "${CSI}2m"
    val BLUE = "${CSI}34m"
    val CYAN = "${CSI}36m"
    val GREEN = "${CSI}32m"
    val YELLOW = "${CSI}33m"
    val RED = "${CSI}31m"
    val CODE_TEXT = "${CSI}38;5;250m"
    val CODE_KEYWORD = "${CSI}38;5;81m"
    val CODE_STRING = "${CSI}38;5;114m"
    val CODE_COMMENT = "${CSI}38;5;244m"
    val CODE_NUMBER = "${CSI}38;5;215m"
    val CODE_LANGUAGE = "${CSI}38;5;117m"

    val CLEAR_SCREEN = "${CSI}2J"
    val CLEAR_LINE = "${CSI}2K"
    val HOME = "${CSI}H"
    val SAVE_CURSOR = "${CSI}s"
    val RESTORE_CURSOR = "${CSI}u"
    val RESET_SCROLL_REGION = "${CSI}r"
    val BLINKING_BAR_CURSOR = "${CSI}5 q"
    val RESET_CURSOR_STYLE = "${CSI}0 q"

    /** Moves the cursor to a 1-based [row], [column]. */
    fun cursor(row: Int, column: Int): String = "$CSI${row};${column}H"

    /** Moves the cursor up by [rows] without changing its column. */
    fun cursorUp(rows: Int): String = if (rows > 0) "$CSI${rows}A" else ""

    /** Moves the cursor down by [rows] without changing its column. */
    fun cursorDown(rows: Int): String = if (rows > 0) "$CSI${rows}B" else ""

    /** Moves the cursor to a 1-based column without changing its row. */
    fun cursorColumn(column: Int): String = "$CSI${column.coerceAtLeast(1)}G"

    /** Restricts scrolling to the inclusive row range [[top], [bottom]] (1-based). */
    fun scrollRegion(top: Int, bottom: Int): String = "$CSI${top};${bottom}r"
}
