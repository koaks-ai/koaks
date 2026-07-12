package org.koaks.cli.tool

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class BashToolTest {
    @Test
    fun descriptionIncludesCurrentOperatingSystemAndShell() {
        assertContains(BashTool.description, "Current operating system: $currentOperatingSystemName.")
        assertContains(BashTool.description, "Current shell: ${BashCommandLine.shellName}.")
        assertContains(BashTool.description, BashCommandLine.commandSyntaxGuidance)
    }

    @Test
    fun outputUsesCompactStatusAndBody() = runBlocking {
        val output = BashTool.execute(BashInput(command = "echo koaks-bash-test"))

        assertContains(output, "✓ exit 0")
        assertContains(output, "koaks-bash-test")
        assertFalse(output.contains("Command:"))
        assertFalse(output.contains("Stats:"))
        assertFalse(output.contains("=== Output ==="))
    }
}
