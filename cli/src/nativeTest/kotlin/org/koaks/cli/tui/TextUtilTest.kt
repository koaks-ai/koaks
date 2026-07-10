package org.koaks.cli.tui

import kotlin.test.Test
import kotlin.test.assertEquals

class TextUtilTest {
    @Test
    fun stripsAnsiSequences() {
        val styled = "${Ansi.BOLD}${Ansi.CYAN}hello${Ansi.RESET}"

        assertEquals("hello", TextUtil.stripAnsi(styled))
    }

    @Test
    fun measuresVisibleWidth() {
        val styled = "${Ansi.BOLD}hi${Ansi.RESET}"

        assertEquals(2, TextUtil.visibleWidth(styled))
    }

    @Test
    fun measuresWideCharactersByTerminalColumns() {
        assertEquals(10, TextUtil.visibleWidth("为什么光标"))
    }

    @Test
    fun padsByVisibleWidth() {
        val styled = "${Ansi.BOLD}hi${Ansi.RESET}"

        assertEquals("$styled  ", TextUtil.padVisible(styled, 4))
    }

    @Test
    fun truncatesByVisibleWidth() {
        val styled = "${Ansi.BOLD}hello${Ansi.RESET}"

        assertEquals("${Ansi.BOLD}hel", TextUtil.truncateVisible(styled, 3))
    }

    @Test
    fun truncatesWideCharactersWithoutOverflowingWidth() {
        assertEquals("为什", TextUtil.truncateVisible("为什么", 4))
        assertEquals("为什", TextUtil.truncateVisible("为什么", 5))
    }
}
