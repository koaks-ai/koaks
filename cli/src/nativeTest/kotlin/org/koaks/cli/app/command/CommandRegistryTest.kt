package org.koaks.cli.app.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandRegistryTest {
    @Test
    fun suggestionsAreDerivedFromAllBuiltinSlashAliases() {
        val registry = CommandRegistry.builtins()

        assertEquals(
            listOf("/help", "/status", "/provider", "/model", "/reasoning", "/skills", "/exit"),
            registry.suggestions.map { it.name },
        )
        assertTrue(registry.isBuiltinCommand("/EXIT"))
    }
}
