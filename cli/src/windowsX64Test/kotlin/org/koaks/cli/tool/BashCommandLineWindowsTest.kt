package org.koaks.cli.tool

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BashCommandLineWindowsTest {
    @Test
    fun registersToolAsPowerShell() {
        assertEquals("PowerShell", BashTool.name)
    }

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

    @Test
    fun rendersPowerShellErrorsAsPlainTextInsteadOfCliXml() {
        val result = NativeCliIo.runBash(
            command = "Get-ChildItem -DefinitelyNotARealParameter",
            maxOutputChars = 10_000,
        )

        assertEquals(1, result.status)
        assertContains(result.output, "Get-ChildItem")
        assertFalse(result.output.contains("#< CLIXML"))
        assertFalse(result.output.contains("<Objs Version="))
    }

    @Test
    fun rendersPowerShellParserErrorsAsPlainText() {
        val result = NativeCliIo.runBash(
            command = "Write-Output (",
            maxOutputChars = 10_000,
        )

        assertEquals(1, result.status)
        assertContains(result.output, "Write-Output (")
        assertFalse(result.output.contains("#< CLIXML"))
        assertFalse(result.output.contains("<Objs Version="))
    }

    @Test
    fun commonLocationCommandsDoNotReturnCliXml() {
        listOf("pwd", "Get-Location").forEach { command ->
            val result = NativeCliIo.runBash(command = command, maxOutputChars = 10_000)

            assertEquals(0, result.status)
            assertFalse(result.output.contains("#< CLIXML"))
            assertFalse(result.output.contains("<Objs Version="))
        }
    }

    @Test
    fun isolatesRawProcessStderrFromProtocolOutput() {
        val result = NativeCliIo.runBash(
            command = "[Console]::Error.WriteLine('#< CLIXML fake startup noise'); Write-Output 'clean-output'",
            maxOutputChars = 10_000,
        )

        assertEquals(0, result.status)
        assertContains(result.output, "clean-output")
        assertFalse(result.output.contains("CLIXML"))
        assertFalse(result.output.contains("fake startup noise"))
    }

    @Test
    fun drainsLargeStdoutAndStderrWithoutDeadlocking() {
        val result = NativeCliIo.runBash(
            command =
                "1..1000 | ForEach-Object { " +
                    "[Console]::Out.WriteLine(('output-{0:D4}-' -f \$_) + ('x' * 80)); " +
                    "[Console]::Error.WriteLine(('noise-{0:D4}-' -f \$_) + ('y' * 80)) }",
            maxOutputChars = 1_000,
        )

        assertEquals(0, result.status)
        assertContains(result.output, "output-0001-")
        assertFalse(result.output.contains("noise-"))
        assertEquals(1_000, result.output.length)
        assertFalse(result.output.contains("KOAKS-RESULT/1"))
        kotlin.test.assertTrue(result.truncated)
    }
}
