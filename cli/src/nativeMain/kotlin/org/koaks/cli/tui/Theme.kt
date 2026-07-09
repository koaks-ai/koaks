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

    /** A bracketed speaker tag, e.g. `[koaks]`. */
    fun prompt(text: String): String = color(Ansi.BOLD + Ansi.CYAN, "[$text]")

    fun inputBorder(text: String): String = color(Ansi.DIM, text)
    fun inputSide(): String = color(Ansi.DIM, "│")
    fun inputPrompt(): String = color(Ansi.BOLD + Ansi.GREEN, "›")

    private fun color(code: String, text: String): String =
        if (enabled) "$code$text${Ansi.RESET}" else text
}
