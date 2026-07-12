package org.koaks.cli.tui

/**
 * Tiny streaming renderer for the small Markdown subset the CLI displays:
 * bold, inline code, and fenced code blocks.
 */
internal class TerminalMarkdownRenderer(
    private val theme: Theme,
    private val blockWidth: Int = DEFAULT_CODE_BLOCK_WIDTH,
    private val onFallback: (MarkdownFallback) -> Unit = {},
    /** Test seam: return true to simulate a broken drain that reports progress without consuming input. */
    private val drainTestHook: (() -> Boolean)? = null,
) {
    internal data class MarkdownFallback(
        val reason: String,
        val state: String,
        val pendingChars: Int,
        val errorType: String? = null,
    )

    private enum class State {
        Normal,
        Bold,
        InlineCode,
        FenceInfo,
        CodeBlock,
    }

    private enum class DrainResult {
        Progressed,
        NeedMoreInput,
    }

    private var state = State.Normal
    private var pending = ""
    private var plainTextMode = false
    private var atLineStart = false
    private val fenceInfo = StringBuilder()
    private var codeLanguage = "text"
    private var codeBlockOpened = false
    private val codeLine = StringBuilder()
    private var codeLineWidth = 0
    private var codeLineOpenDrawn = false
    private var codeLineHasEmittedChunk = false
    private val safeBlockWidth: Int = blockWidth.coerceAtLeast(MIN_CODE_BLOCK_WIDTH)
    private val codeContentWidth: Int = safeBlockWidth - CODE_BLOCK_HORIZONTAL_CHROME

    fun render(text: String): String {
        if (plainTextMode) return text
        pending += text
        return drain(final = false)
    }

    fun finish(): String = if (plainTextMode) "" else drain(final = true)

    private fun drain(final: Boolean): String {
        val out = StringBuilder()

        while (pending.isNotEmpty()) {
            val pendingBefore = pending
            val stateBefore = state
            val stepOut = StringBuilder()
            val result = try {
                if (drainTestHook?.invoke() == true) {
                    DrainResult.Progressed
                } else {
                    when (state) {
                        State.Normal -> drainNormal(stepOut, final)
                        State.Bold -> drainDelimitedSpan(stepOut, "**", theme::bold, final)
                        State.InlineCode -> drainDelimitedSpan(stepOut, "`", theme::inlineCode, final)
                        State.FenceInfo -> drainFenceInfo(stepOut, final)
                        State.CodeBlock -> drainCodeBlock(stepOut, final)
                    }
                }
            } catch (error: Exception) {
                pending = pendingBefore
                fallbackToPlainText(out, reason = FALLBACK_EXCEPTION, error = error)
                return out.toString()
            }

            when (result) {
                DrainResult.NeedMoreInput -> {
                    if (stepOut.isNotEmpty() || pending != pendingBefore || state != stateBefore) {
                        pending = pendingBefore
                        fallbackToPlainText(out, reason = FALLBACK_NO_PROGRESS)
                        return out.toString()
                    }
                    break
                }

                DrainResult.Progressed -> {
                    if (pending.length >= pendingBefore.length) {
                        pending = pendingBefore
                        fallbackToPlainText(out, reason = FALLBACK_NO_PROGRESS)
                        return out.toString()
                    }
                    out.append(stepOut)
                }
            }
        }

        if (final) {
            val pendingBefore = pending
            val finalOut = StringBuilder()
            try {
                finalizeMarkdown(finalOut)
                out.append(finalOut)
            } catch (error: Exception) {
                pending = pendingBefore
                fallbackToPlainText(out, reason = FALLBACK_EXCEPTION, error = error)
            }
        }

        return out.toString()
    }

    private fun finalizeMarkdown(out: StringBuilder) {
        when (state) {
            State.Normal -> Unit
            State.Bold -> {
                state = State.Normal
            }

            State.InlineCode -> {
                state = State.Normal
            }

            State.FenceInfo -> {
                if (codeBlockOpened) {
                    closeCodeBlock(out)
                } else {
                    appendPlain(out, "```")
                    appendPlain(out, fenceInfo.toString())
                }
                fenceInfo.clear()
                state = State.Normal
            }

            State.CodeBlock -> {
                emitCodeText(out, pending)
                pending = ""
                closeCodeBlock(out)
                fenceInfo.clear()
                state = State.Normal
            }
        }

        if (pending.isNotEmpty()) {
            appendPlain(out, pending)
            pending = ""
        }
    }

    private fun drainNormal(out: StringBuilder, final: Boolean): DrainResult {
        if (pending.startsWith("```")) {
            if (!atLineStart) appendPlain(out, "\n")
            consume(3)
            fenceInfo.clear()
            codeBlockOpened = false
            state = State.FenceInfo
            // If the language newline is not in this chunk yet, open the frame now so the
            // stream does not appear frozen while waiting for it.
            if (!pending.contains('\n')) {
                if (pending.isNotEmpty()) {
                    fenceInfo.append(pending)
                    pending = ""
                }
                openCodeBlock(out)
            }
            return DrainResult.Progressed
        }

        if (pending.startsWith("**")) {
            consume(2)
            state = State.Bold
            return DrainResult.Progressed
        }

        val specialAt = pending.indexOfAny(charArrayOf('*', '`'))
        if (specialAt > 0) {
            appendPlain(out, pending.substring(0, specialAt))
            consume(specialAt)
            return DrainResult.Progressed
        }

        val first = pending[0]
        if (first == '`') {
            if (!final && pending.length < 3 && pending.all { it == '`' }) {
                return DrainResult.NeedMoreInput
            }
            consume(1)
            state = State.InlineCode
            return DrainResult.Progressed
        }

        if (first == '*' && !final && pending.length == 1) return DrainResult.NeedMoreInput

        appendPlain(out, first.toString())
        consume(1)
        return DrainResult.Progressed
    }

    private fun drainDelimitedSpan(
        out: StringBuilder,
        delimiter: String,
        style: (String) -> String,
        final: Boolean,
    ): DrainResult {
        val closeAt = pending.indexOf(delimiter)
        if (closeAt >= 0) {
            appendStyled(out, pending.substring(0, closeAt), style)
            consume(closeAt + delimiter.length)
            state = State.Normal
            return DrainResult.Progressed
        }

        val hold = if (!final && delimiter == "**" && pending.endsWith("*")) 1 else 0
        val take = pending.length - hold
        if (take > 0) {
            appendStyled(out, pending.substring(0, take), style)
            consume(take)
            return DrainResult.Progressed
        }
        return DrainResult.NeedMoreInput
    }

    private fun drainFenceInfo(out: StringBuilder, final: Boolean): DrainResult {
        val newlineAt = pending.indexOf('\n')
        if (newlineAt >= 0) {
            fenceInfo.append(pending.substring(0, newlineAt))
            consume(newlineAt + 1)
            refreshCodeLanguage()
            if (!codeBlockOpened) openCodeBlock(out)
            state = State.CodeBlock
            return DrainResult.Progressed
        }

        if (final) return DrainResult.NeedMoreInput

        fenceInfo.append(pending)
        pending = ""
        refreshCodeLanguage()
        if (!codeBlockOpened) openCodeBlock(out)
        return DrainResult.Progressed
    }

    private fun drainCodeBlock(out: StringBuilder, final: Boolean): DrainResult {
        val closeAt = pending.indexOf("```")
        if (closeAt >= 0) {
            emitCodeText(out, pending.substring(0, closeAt))
            consume(closeAt + 3)
            closeCodeBlock(out)
            fenceInfo.clear()
            state = State.Normal
            consumeFenceTrailingNewline()
            return DrainResult.Progressed
        }

        val hold = if (!final) pending.trailingBacktickCount().coerceAtMost(2) else 0
        val take = pending.length - hold
        if (take > 0) {
            emitCodeText(out, pending.substring(0, take))
            consume(take)
            return DrainResult.Progressed
        }
        return DrainResult.NeedMoreInput
    }

    private fun consumeFenceTrailingNewline() {
        if (pending.startsWith("\r\n")) {
            consume(2)
        } else if (pending.startsWith("\n")) {
            consume(1)
        }
    }

    private fun fallbackToPlainText(out: StringBuilder, reason: String, error: Exception? = null) {
        val fallback = MarkdownFallback(
            reason = reason,
            state = state.name.lowercase(),
            pendingChars = pending.length,
            errorType = error?.let { it::class.simpleName ?: "Exception" },
        )
        if (pending.isNotEmpty()) appendPlain(out, pending)
        pending = ""
        plainTextMode = true
        resetParserState()
        try {
            onFallback(fallback)
        } catch (_: Exception) {
            // Diagnostics are best-effort and must never break response rendering.
        }
    }

    private fun resetParserState() {
        state = State.Normal
        fenceInfo.clear()
        codeLanguage = "text"
        codeBlockOpened = false
        codeLine.clear()
        codeLineWidth = 0
        codeLineOpenDrawn = false
        codeLineHasEmittedChunk = false
    }

    private fun consume(count: Int) {
        pending = pending.substring(count)
    }

    private fun appendStyled(out: StringBuilder, text: String, style: (String) -> String) {
        out.append(style(text))
        markOutput(text)
    }

    private fun appendPlain(out: StringBuilder, text: String) {
        out.append(text)
        markOutput(text)
    }

    private fun markOutput(text: String) {
        if (text.isNotEmpty()) atLineStart = text.endsWith("\n")
    }

    private fun refreshCodeLanguage() {
        codeLanguage = fenceInfo.toString().trim().substringBefore(' ').ifBlank { "text" }
    }

    private fun openCodeBlock(out: StringBuilder) {
        refreshCodeLanguage()
        codeLine.clear()
        codeLineWidth = 0
        codeLineOpenDrawn = false
        codeLineHasEmittedChunk = false
        codeBlockOpened = true

        appendStyled(out, "┌─ ", theme::codeBlockFrame)
        appendStyled(out, codeLanguage, theme::codeBlockLanguage)
        val remaining = safeBlockWidth -
            TextUtil.visibleWidth("┌─ ") -
            TextUtil.visibleWidth(codeLanguage) -
            TextUtil.visibleWidth("┐")
        appendStyled(out, TextUtil.rule('─', remaining), theme::codeBlockFrame)
        appendStyled(out, "┐\n", theme::codeBlockFrame)
    }

    private fun emitCodeText(out: StringBuilder, text: String) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        var index = 0
        while (index < normalized.length) {
            if (normalized[index] == '\n') {
                finishCodeLine(out)
                index++
            } else {
                val displayText = normalized.nextDisplayText(index)
                val displayWidth = TextUtil.visibleWidth(displayText)
                if (codeLine.isNotEmpty() && codeLineWidth + displayWidth > codeContentWidth) {
                    emitWrappedCodeLine(out)
                }
                codeLine.append(displayText)
                codeLineWidth += displayWidth
                if (codeLineWidth >= codeContentWidth) {
                    emitWrappedCodeLine(out)
                }
                index += displayText.length
            }
        }
        // Redraw the in-progress line as a full highlighted + padded row so streaming
        // stays visible without breaking the right border or syntax colors.
        redrawOpenCodeLine(out)
    }

    private fun redrawOpenCodeLine(out: StringBuilder) {
        if (codeLine.isEmpty()) return
        if (codeLineOpenDrawn) out.append('\r')
        emitHighlightedCodeLine(out, codeLine.toString(), terminate = false)
        codeLineOpenDrawn = true
    }

    private fun finishCodeLine(out: StringBuilder) {
        when {
            codeLineOpenDrawn || codeLine.isNotEmpty() -> {
                if (codeLineOpenDrawn) out.append('\r')
                emitHighlightedCodeLine(out, codeLine.toString(), terminate = true)
            }

            !codeLineHasEmittedChunk -> {
                emitHighlightedCodeLine(out, "", terminate = true)
            }
        }
        codeLine.clear()
        codeLineWidth = 0
        codeLineOpenDrawn = false
        codeLineHasEmittedChunk = false
    }

    private fun emitWrappedCodeLine(out: StringBuilder) {
        if (codeLineOpenDrawn) out.append('\r')
        emitHighlightedCodeLine(out, codeLine.toString(), terminate = true)
        codeLine.clear()
        codeLineWidth = 0
        codeLineOpenDrawn = false
        codeLineHasEmittedChunk = true
    }

    private fun emitHighlightedCodeLine(out: StringBuilder, line: String, terminate: Boolean) {
        appendStyled(out, "│ ", theme::codeBlockFrame)
        val highlighted = highlightedCode(line)
        out.append(highlighted)
        markOutput(TextUtil.stripAnsi(highlighted))
        val padding = codeContentWidth - TextUtil.visibleWidth(highlighted)
        appendStyled(out, TextUtil.rule(' ', padding), theme::codeBlockText)
        appendStyled(out, " │", theme::codeBlockFrame)
        if (terminate) {
            out.append('\n')
            atLineStart = true
            codeLineHasEmittedChunk = true
        }
    }

    private fun closeCodeBlock(out: StringBuilder) {
        if (codeLine.isNotEmpty() || codeLineOpenDrawn) {
            finishCodeLine(out)
            codeLineHasEmittedChunk = false
        }
        appendStyled(out, "└", theme::codeBlockFrame)
        appendStyled(out, TextUtil.rule('─', safeBlockWidth - 2), theme::codeBlockFrame)
        appendStyled(out, "┘\n", theme::codeBlockFrame)
        fenceInfo.clear()
        codeLanguage = "text"
        codeBlockOpened = false
        codeLine.clear()
        codeLineWidth = 0
        codeLineOpenDrawn = false
        codeLineHasEmittedChunk = false
    }

    private fun highlightedCode(line: String): String {
        return when (val language = normalizedCodeLanguage()) {
            "html", "xml" -> highlightedMarkup(line)
            "python" -> highlightedKeywordLine(line, KEYWORDS_BY_LANGUAGE["python"].orEmpty(), lineComment = "#", stringQuotes = "\"'")
            "c", "cpp", "java", "rust", "javascript", "kotlin" -> highlightedKeywordLine(
                line = line,
                keywords = KEYWORDS_BY_LANGUAGE[language].orEmpty(),
                lineComment = "//",
                stringQuotes = if (language == "javascript") "\"'`" else "\"'",
            )

            else -> theme.codeBlockText(line)
        }
    }

    private fun highlightedKeywordLine(
        line: String,
        keywords: Set<String>,
        lineComment: String,
        stringQuotes: String,
    ): String {
        val out = StringBuilder()
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                line.startsWith(lineComment, index) -> {
                    out.append(theme.codeComment(line.substring(index)))
                    return out.toString()
                }

                char in stringQuotes -> {
                    val end = line.findStringEnd(index, char)
                    out.append(theme.codeString(line.substring(index, end)))
                    index = end
                }

                char.isDigit() -> {
                    val end = line.consumeWhile(index) { it.isDigit() || it == '.' || it == '_' }
                    out.append(theme.codeNumber(line.substring(index, end)))
                    index = end
                }

                char.isIdentifierStart() -> {
                    val end = line.consumeWhile(index) { it.isIdentifierPart() }
                    val token = line.substring(index, end)
                    val style = if (token in keywords) theme::codeKeyword else theme::codeBlockText
                    out.append(style(token))
                    index = end
                }

                else -> {
                    out.append(theme.codeBlockText(char.toString()))
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun highlightedMarkup(line: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < line.length) {
            when {
                line.startsWith("<!--", index) -> {
                    val end = line.indexOf("-->", startIndex = index + 4).let {
                        if (it >= 0) it + 3 else line.length
                    }
                    out.append(theme.codeComment(line.substring(index, end)))
                    index = end
                }

                line[index] == '<' -> {
                    val end = line.indexOf('>', startIndex = index + 1).let {
                        if (it >= 0) it + 1 else line.length
                    }
                    out.append(highlightedMarkupTag(line.substring(index, end)))
                    index = end
                }

                else -> {
                    out.append(theme.codeBlockText(line[index].toString()))
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun highlightedMarkupTag(tag: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < tag.length) {
            val char = tag[index]
            when {
                char == '"' || char == '\'' -> {
                    val end = tag.findStringEnd(index, char)
                    out.append(theme.codeString(tag.substring(index, end)))
                    index = end
                }

                char.isIdentifierStart() -> {
                    val end = tag.consumeWhile(index) { it.isIdentifierPart() || it == '-' || it == ':' }
                    out.append(theme.codeKeyword(tag.substring(index, end)))
                    index = end
                }

                else -> {
                    out.append(theme.codeBlockText(char.toString()))
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun String.trailingBacktickCount(): Int {
        var count = 0
        var index = length - 1
        while (index >= 0 && this[index] == '`') {
            count++
            index--
        }
        return count
    }

    private fun String.findStringEnd(start: Int, quote: Char): Int {
        var escaped = false
        var index = start + 1
        while (index < length) {
            val char = this[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                return index + 1
            }
            index++
        }
        return length
    }

    private fun String.consumeWhile(start: Int, predicate: (Char) -> Boolean): Int {
        var index = start
        while (index < length && predicate(this[index])) index++
        return index
    }

    private fun String.nextDisplayText(index: Int): String {
        val first = this[index]
        return if (
            first.isHighSurrogate() &&
            index + 1 < length &&
            this[index + 1].isLowSurrogate()
        ) {
            substring(index, index + 2)
        } else {
            first.toString()
        }
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || isLetter()

    private fun Char.isIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()

    private fun normalizedCodeLanguage(): String =
        when (codeLanguage.lowercase()) {
            "kt", "kts", "kotlin" -> "kotlin"
            "c", "h" -> "c"
            "cc", "cpp", "c++", "cxx", "hh", "hpp", "hxx" -> "cpp"
            "java" -> "java"
            "py", "python", "python3" -> "python"
            "rs", "rust" -> "rust"
            "js", "javascript", "node", "nodejs" -> "javascript"
            "htm", "html" -> "html"
            "xml" -> "xml"
            else -> codeLanguage.lowercase()
        }

    private companion object {
        const val FALLBACK_NO_PROGRESS = "no_progress"
        const val FALLBACK_EXCEPTION = "exception"
        const val DEFAULT_CODE_BLOCK_WIDTH = 78
        const val MIN_CODE_BLOCK_WIDTH = 16
        const val CODE_BLOCK_HORIZONTAL_CHROME = 4
        val KEYWORDS_BY_LANGUAGE = mapOf(
            "kotlin" to setOf(
                "as",
                "break",
                "class",
                "continue",
                "do",
                "else",
                "false",
                "for",
                "fun",
                "if",
                "in",
                "interface",
                "is",
                "null",
                "object",
                "package",
                "return",
                "super",
                "this",
                "throw",
                "true",
                "try",
                "typealias",
                "typeof",
                "val",
                "var",
                "when",
                "while",
            ),
            "c" to setOf(
                "auto",
                "break",
                "case",
                "char",
                "const",
                "continue",
                "default",
                "do",
                "double",
                "else",
                "enum",
                "extern",
                "float",
                "for",
                "goto",
                "if",
                "inline",
                "int",
                "long",
                "register",
                "restrict",
                "return",
                "short",
                "signed",
                "sizeof",
                "static",
                "struct",
                "switch",
                "typedef",
                "union",
                "unsigned",
                "void",
                "volatile",
                "while",
            ),
            "cpp" to setOf(
                "alignas",
                "auto",
                "bool",
                "break",
                "case",
                "class",
                "concept",
                "const",
                "constexpr",
                "continue",
                "decltype",
                "default",
                "delete",
                "do",
                "else",
                "enum",
                "explicit",
                "export",
                "false",
                "for",
                "friend",
                "if",
                "inline",
                "namespace",
                "new",
                "noexcept",
                "nullptr",
                "operator",
                "private",
                "protected",
                "public",
                "return",
                "static",
                "struct",
                "switch",
                "template",
                "this",
                "throw",
                "true",
                "try",
                "typename",
                "using",
                "virtual",
                "void",
                "while",
            ),
            "java" to setOf(
                "abstract",
                "boolean",
                "break",
                "byte",
                "case",
                "catch",
                "char",
                "class",
                "const",
                "continue",
                "default",
                "do",
                "double",
                "else",
                "enum",
                "extends",
                "false",
                "final",
                "finally",
                "float",
                "for",
                "if",
                "implements",
                "import",
                "instanceof",
                "int",
                "interface",
                "long",
                "new",
                "null",
                "package",
                "private",
                "protected",
                "public",
                "return",
                "short",
                "static",
                "super",
                "switch",
                "this",
                "throw",
                "throws",
                "true",
                "try",
                "void",
                "while",
            ),
            "python" to setOf(
                "and",
                "as",
                "assert",
                "async",
                "await",
                "break",
                "class",
                "continue",
                "def",
                "del",
                "elif",
                "else",
                "except",
                "False",
                "finally",
                "for",
                "from",
                "global",
                "if",
                "import",
                "in",
                "is",
                "lambda",
                "None",
                "nonlocal",
                "not",
                "or",
                "pass",
                "raise",
                "return",
                "True",
                "try",
                "while",
                "with",
                "yield",
            ),
            "rust" to setOf(
                "as",
                "async",
                "await",
                "break",
                "const",
                "continue",
                "crate",
                "dyn",
                "else",
                "enum",
                "extern",
                "false",
                "fn",
                "for",
                "if",
                "impl",
                "in",
                "let",
                "loop",
                "match",
                "mod",
                "move",
                "mut",
                "pub",
                "ref",
                "return",
                "self",
                "Self",
                "static",
                "struct",
                "super",
                "trait",
                "true",
                "type",
                "unsafe",
                "use",
                "where",
                "while",
            ),
            "javascript" to setOf(
                "async",
                "await",
                "break",
                "case",
                "catch",
                "class",
                "const",
                "continue",
                "debugger",
                "default",
                "delete",
                "do",
                "else",
                "export",
                "extends",
                "false",
                "finally",
                "for",
                "from",
                "function",
                "if",
                "import",
                "in",
                "instanceof",
                "let",
                "new",
                "null",
                "of",
                "return",
                "static",
                "super",
                "switch",
                "this",
                "throw",
                "true",
                "try",
                "typeof",
                "undefined",
                "var",
                "void",
                "while",
                "yield",
            ),
        )
    }
}
