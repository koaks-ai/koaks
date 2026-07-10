package org.koaks.cli.tui

/**
 * Semantic styling. Callers ask for meaning ("this is a label", "this is an error"),
 * not raw color codes, so the palette can change in one place. When [enabled] is false
 * (piped output, `NO_COLOR`, `TERM=dumb`) every helper returns its text unchanged.
 */
internal class Theme(val enabled: Boolean) {

    fun label(text: String): String = color(Ansi.BOLD + Ansi.GREEN, text)
    fun dim(text: String): String = color(Ansi.DIM, text)
    fun warn(text: String): String = color(Ansi.YELLOW, text)
    fun error(text: String): String = color(Ansi.RED, text)
    fun command(text: String): String = color(Ansi.BLUE, text)
    fun bold(text: String): String = color(Ansi.BOLD, text)
    fun inlineCode(text: String): String = color(Ansi.BLUE, text)
    fun codeBlockFrame(text: String): String = color(Ansi.DIM, text)
    fun codeBlockLanguage(text: String): String = color(Ansi.BOLD + Ansi.CODE_LANGUAGE, text)
    fun codeBlockText(text: String): String = color(Ansi.CODE_TEXT, text)
    fun codeKeyword(text: String): String = color(Ansi.BOLD + Ansi.CODE_KEYWORD, text)
    fun codeString(text: String): String = color(Ansi.CODE_STRING, text)
    fun codeComment(text: String): String = color(Ansi.CODE_COMMENT, text)
    fun codeNumber(text: String): String = color(Ansi.CODE_NUMBER, text)

    /** The assistant message marker shown before the first text delta. */
    fun assistantMark(): String = color(Ansi.BOLD + Ansi.CYAN, "◆")

    fun inputBorder(text: String): String = color(Ansi.DIM, text)
    fun inputSide(): String = color(Ansi.DIM, "│")
    fun inputPrompt(): String = color(Ansi.BOLD + Ansi.GREEN, "›")
    fun commandMenuSelection(text: String): String = color(Ansi.BOLD + Ansi.BLUE, text)

    private fun color(code: String, text: String): String =
        if (enabled) "$code$text${Ansi.RESET}" else text
}
