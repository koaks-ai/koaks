package org.koaks.cli.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubagentTypeTest {
    @Test
    fun parsesKnownTypes() {
        assertEquals(SubagentType.GENERAL, SubagentType.parse(null))
        assertEquals(SubagentType.GENERAL, SubagentType.parse(""))
        assertEquals(SubagentType.GENERAL, SubagentType.parse("general"))
        assertEquals(SubagentType.EXPLORE, SubagentType.parse("Explore"))
        assertEquals(SubagentType.WORKER, SubagentType.parse("worker"))
    }

    @Test
    fun rejectsUnknownType() {
        val error = assertFailsWith<IllegalStateException> {
            SubagentType.parse("researcher")
        }
        assertTrue(error.message!!.contains("Unknown subagent_type"))
    }

    @Test
    fun exploreIsReadOnly() {
        assertTrue(SubagentType.EXPLORE.instructions.contains("read-only", ignoreCase = true))
        assertTrue(SubagentType.GENERAL.instructions.contains("Write"))
        assertTrue(SubagentType.WORKER.instructions.contains("Write"))
    }
}
