package org.koaks.cli.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalMarkdownRendererTest {
    @Test
    fun fallsBackToPlainTextWhenDrainMakesNoProgress() {
        val fallbacks = mutableListOf<TerminalMarkdownRenderer.MarkdownFallback>()
        val renderer = TerminalMarkdownRenderer(
            theme = Theme(enabled = false),
            onFallback = fallbacks::add,
            drainTestHook = { true },
        )

        assertEquals("plain `text`", renderer.render("plain `text`"))
        assertEquals(" **next**", renderer.render(" **next**"))
        assertEquals("", renderer.finish())

        assertEquals(1, fallbacks.size)
        assertEquals("no_progress", fallbacks.single().reason)
        assertEquals("normal", fallbacks.single().state)
        assertEquals(12, fallbacks.single().pendingChars)
        assertNull(fallbacks.single().errorType)
    }

    @Test
    fun fallsBackToPlainTextWhenDrainThrows() {
        val fallbacks = mutableListOf<TerminalMarkdownRenderer.MarkdownFallback>()
        var fail = false
        val renderer = TerminalMarkdownRenderer(
            theme = Theme(enabled = false),
            onFallback = fallbacks::add,
            drainTestHook = {
                if (fail) throw IllegalStateException("sensitive response text")
                false
            },
        )

        assertEquals("before ", renderer.render("before `"))
        fail = true
        assertEquals("`code", renderer.render("code"))
        assertEquals(" after", renderer.render(" after"))
        assertEquals("", renderer.finish())

        assertEquals(1, fallbacks.size)
        assertEquals("exception", fallbacks.single().reason)
        assertEquals("IllegalStateException", fallbacks.single().errorType)
    }

    @Test
    fun ignoresFallbackCallbackFailures() {
        val renderer = TerminalMarkdownRenderer(
            theme = Theme(enabled = false),
            onFallback = { throw IllegalStateException("trace failed") },
            drainTestHook = { true },
        )

        assertEquals("still visible", renderer.render("still visible"))
        assertEquals(" and continuing", renderer.render(" and continuing"))
    }

    @Test
    fun rendersInlineMarkdownAtEveryTwoChunkBoundary() {
        val source = "A **bold** value and `inline code`."
        val expected = renderInChunks(source)

        for (splitAt in 0..source.length) {
            assertEquals(
                expected,
                renderInChunks(source.substring(0, splitAt), source.substring(splitAt)),
                "splitAt=$splitAt",
            )
        }
    }

    @Test
    fun rendersMarkdownOneCharacterAtATime() {
        val cases = listOf(
            "A **bold** value and `inline code`.",
            "single opener `content",
            "double opener ``content",
            "stars * and **bold**",
        )

        for (source in cases) {
            assertEquals(
                renderInChunks(source),
                renderInChunks(*source.map(Char::toString).toTypedArray()),
                source,
            )
        }
    }

    @Test
    fun completesFencedCodeAtEveryChunkBoundaryWithoutFallback() {
        val source = "```kotlin\nval answer = 42\n```\n"

        for (splitAt in 0..source.length) {
            val fallbacks = mutableListOf<TerminalMarkdownRenderer.MarkdownFallback>()
            val renderer = TerminalMarkdownRenderer(
                theme = Theme(enabled = false),
                onFallback = fallbacks::add,
            )
            renderer.render(source.substring(0, splitAt))
            renderer.render(source.substring(splitAt))
            renderer.finish()
            assertEquals(emptyList(), fallbacks, "splitAt=$splitAt")
        }
    }

    @Test
    fun completesFencedCodeOneCharacterAtATimeWithoutFallback() {
        val source = "```kotlin\nval answer = 42\n```\n"
        val fallbacks = mutableListOf<TerminalMarkdownRenderer.MarkdownFallback>()
        val renderer = TerminalMarkdownRenderer(
            theme = Theme(enabled = false),
            onFallback = fallbacks::add,
        )

        source.forEach { renderer.render(it.toString()) }
        renderer.finish()

        assertEquals(emptyList(), fallbacks)
    }

    private fun renderInChunks(vararg chunks: String): String {
        val renderer = TerminalMarkdownRenderer(Theme(enabled = false))
        return buildString {
            chunks.forEach { append(renderer.render(it)) }
            append(renderer.finish())
        }
    }
}
