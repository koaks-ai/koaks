package org.koaks.framework.utils.json

/**
 * Tolerant extraction of a single JSON object/array from a model's free-form text.
 * Handles the common ways models wrap structured output:
 *  - ```` ```json ... ``` ```` and bare ```` ``` ... ``` ```` fences
 *  - leading prose before the object ("Here is the result: { ... }")
 *
 * Returns the substring from the first `{`/`[` to its matching close (brace-aware,
 * string-literal-aware), or the trimmed input if no object is found (letting the
 * caller's decode surface a precise error).
 */
object JsonExtractor {

    fun extract(raw: String): String {
        val stripped = stripFences(raw).trim()
        val start = stripped.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return stripped
        val open = stripped[start]
        val close = if (open == '{') '}' else ']'
        val end = matchingClose(stripped, start, open, close)
        return if (end > start) stripped.substring(start, end + 1) else stripped.substring(start)
    }

    private fun stripFences(text: String): String {
        val fence = "```"
        if (!text.contains(fence)) return text
        val firstFence = text.indexOf(fence)
        val afterOpen = text.indexOf('\n', firstFence).let { if (it < 0) firstFence + fence.length else it + 1 }
        val closeFence = text.indexOf(fence, afterOpen)
        return if (closeFence > afterOpen) text.substring(afterOpen, closeFence) else text.substring(afterOpen)
    }

    /** Finds the index of the close char matching the opener at [start], ignoring braces in strings. */
    private fun matchingClose(s: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == open -> depth++
                !inString && c == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
