package org.koaks.cli.tool

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BashCommandLineWindowsTest {
    @Test
    fun runsPowerShellSyntax() {
        val result = NativeCliIo.runBash(
            command = "\$items = @(1, 2, 3); Write-Output \$items.Count",
            maxOutputChars = 1_000,
        )

        assertEquals(0, result.status)
        assertContains(result.output, "3")
    }

    @Test
    fun preservesTextThatWouldBeSpecialToCmd() {
        val result = NativeCliIo.runBash(
            command = """Write-Output "a&b|c<d>e%PATH%"""",
            maxOutputChars = 1_000,
        )

        assertEquals(0, result.status)
        assertContains(result.output, "a&b|c<d>e%PATH%")
    }

    @Test
    fun returnsNativeProcessExitCode() {
        val result = NativeCliIo.runBash(
            command = "cmd.exe /c exit 7",
            maxOutputChars = 1_000,
        )

        assertEquals(7, result.status)
    }
}
