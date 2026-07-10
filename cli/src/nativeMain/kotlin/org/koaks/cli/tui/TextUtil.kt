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
    fun visibleWidth(text: String): Int {
        val plain = stripAnsi(text)
        var width = 0
        var index = 0
        while (index < plain.length) {
            val codePoint = plain.codePointAt(index)
            width += codePoint.displayWidth()
            index += codePoint.charCount()
        }
        return width
    }

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
                val codePoint = content.codePointAt(index)
                val charCount = codePoint.charCount()
                val charWidth = codePoint.displayWidth()
                if (charWidth > 0 && visible + charWidth > width) break
                builder.append(content.substring(index, index + charCount))
                visible += charWidth
                index += charCount
            }
        }

        return builder.toString()
    }

    private fun String.codePointAt(index: Int): Int {
        val first = this[index]
        return if (
            first.isHighSurrogate() &&
            index + 1 < length &&
            this[index + 1].isLowSurrogate()
        ) {
            0x10000 + ((first.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
        } else {
            first.code
        }
    }

    private fun Int.charCount(): Int =
        if (this >= 0x10000) 2 else 1

    private fun Int.displayWidth(): Int = when {
        this == 0 -> 0
        this < 32 || this in 0x7F..0x9F -> 0
        this in 0x0300..0x036F -> 0
        this in 0x1AB0..0x1AFF -> 0
        this in 0x1DC0..0x1DFF -> 0
        this in 0x20D0..0x20FF -> 0
        this in 0xFE00..0xFE0F -> 0
        this in 0xFE20..0xFE2F -> 0
        this == 0x200D -> 0
        isWideCodePoint() -> 2
        else -> 1
    }

    private fun Int.isWideCodePoint(): Boolean =
        this in 0x1100..0x115F ||
            this in 0x231A..0x231B ||
            this in 0x2329..0x232A ||
            this in 0x23E9..0x23EC ||
            this in 0x23F0..0x23F0 ||
            this in 0x23F3..0x23F3 ||
            this in 0x25FD..0x25FE ||
            this in 0x2614..0x2615 ||
            this in 0x2648..0x2653 ||
            this in 0x267F..0x267F ||
            this in 0x2693..0x2693 ||
            this in 0x26A1..0x26A1 ||
            this in 0x26AA..0x26AB ||
            this in 0x26BD..0x26BE ||
            this in 0x26C4..0x26C5 ||
            this in 0x26CE..0x26CE ||
            this in 0x26D4..0x26D4 ||
            this in 0x26EA..0x26EA ||
            this in 0x26F2..0x26F3 ||
            this in 0x26F5..0x26F5 ||
            this in 0x26FA..0x26FA ||
            this in 0x26FD..0x26FD ||
            this in 0x2705..0x2705 ||
            this in 0x270A..0x270B ||
            this in 0x2728..0x2728 ||
            this in 0x274C..0x274C ||
            this in 0x274E..0x274E ||
            this in 0x2753..0x2755 ||
            this in 0x2757..0x2757 ||
            this in 0x2795..0x2797 ||
            this in 0x27B0..0x27B0 ||
            this in 0x27BF..0x27BF ||
            this in 0x2E80..0xA4CF ||
            this in 0xAC00..0xD7A3 ||
            this in 0xF900..0xFAFF ||
            this in 0xFE10..0xFE19 ||
            this in 0xFE30..0xFE6F ||
            this in 0xFF00..0xFF60 ||
            this in 0xFFE0..0xFFE6 ||
            this in 0x1F300..0x1FAFF ||
            this in 0x20000..0x3FFFD

    private fun Char.isAnsiCommandTerminator(): Boolean =
        this in '@'..'~'
}
