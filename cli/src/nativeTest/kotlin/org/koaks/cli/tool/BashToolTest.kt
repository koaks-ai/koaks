package org.koaks.cli.tool

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains

class BashToolTest {
    @Test
    fun descriptionIncludesCurrentOperatingSystemAndShell() {
        assertContains(BashTool.description, "Current operating system: $currentOperatingSystemName.")
        assertContains(BashTool.description, "Current shell: ${BashCommandLine.shellName}.")
        assertContains(BashTool.description, BashCommandLine.commandSyntaxGuidance)
    }

    @Test
    fun outputUsesCompactStatsHeader() = runBlocking {
        val output = BashTool.execute(BashInput(command = "echo koaks-bash-test"))

        assertContains(output, "Command: echo koaks-bash-test")
        assertContains(output, "Stats: Status=0")
        assertContains(output, "=== Output ===")
        assertContains(output, "koaks-bash-test")
    }
}
