package org.koaks.cli.tui

/**
 * Pure text helpers for terminal rendering: measuring and padding strings by their
 * *visible* width (ANSI escapes excluded) and drawing horizontal rules. No I/O, so
 * every function here is trivially unit-testable.
 */
internal object TextUtil {

    /** ESC (char 27); starts every ANSI control sequence. */
    private val ESC: Char = Char(27)

    /** Strips ANSI CSI escape sequences (`ESC [ ... cmd`), leaving visible text. */
    fun stripAnsi(text: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == ESC && index + 1 < text.length && text[index + 1] == '[') {
                index += 2
                while (index < text.length && !text[index].isAnsiCommandTerminator()) index += 1
                if (index < text.length) index += 1
            } else {
                builder.append(text[index])
                index += 1
            }
        }
        return builder.toString()
    }

    /** The on-screen column count of [text] once ANSI escapes are removed. */
    fun visibleWidth(text: String): Int = stripAnsi(text).length

    /** Repeats [ch] to build a horizontal rule of [width] columns. */
    fun rule(ch: Char, width: Int): String = ch.toString().repeat(width.coerceAtLeast(0))

    /**
     * Right-pads [content] with spaces to a target *visible* [width]. If [content] is
     * already wider it is returned unchanged (callers that must truncate should do so
     * before calling). ANSI escapes in [content] do not count toward the width.
     */
    fun padVisible(content: String, width: Int): String {
        val padding = (width - visibleWidth(content)).coerceAtLeast(0)
        return content + " ".repeat(padding)
    }

    /** Truncates to [width] visible columns while preserving whole ANSI sequences. */
    fun truncateVisible(content: String, width: Int): String {
        if (width <= 0) return ""

        val builder = StringBuilder()
        var visible = 0
        var index = 0

        while (index < content.length && visible < width) {
            if (content[index] == ESC && index + 1 < content.length && content[index + 1] == '[') {
                val start = index
                index += 2
                while (index < content.length && !content[index].isAnsiCommandTerminator()) index += 1
                if (index < content.length) index += 1
                builder.append(content.substring(start, index))
            } else {
                builder.append(content[index])
                visible += 1
                index += 1
            }
        }

        return builder.toString()
    }

    private fun Char.isAnsiCommandTerminator(): Boolean =
        this in '@'..'~'
}
