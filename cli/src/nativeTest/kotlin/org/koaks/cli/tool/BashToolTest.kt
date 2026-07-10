package org.koaks.cli.tool

import kotlin.test.Test
import kotlin.test.assertContains

class BashToolTest {
    @Test
    fun descriptionIncludesCurrentOperatingSystemAndShell() {
        assertContains(BashTool.description, "Current operating system: $currentOperatingSystemName.")
        assertContains(BashTool.description, "Current shell: ${BashCommandLine.shellName}.")
        assertContains(BashTool.description, "Use command syntax for the current operating system and shell.")
    }
}
