package org.koaks.framework.memory

import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptRepairTest {
    @Test
    fun synthesizes_error_results_for_orphan_calls_without_mutating_storage() {
        val call = ModelItem.ToolCall(ref = ItemRef("call_1"), name = "search", arguments = "{}")
        val stored = listOf(ModelItem.user("q"), call)
        val online = repairTranscript(stored)
        assertEquals(2, stored.size)
        assertEquals(3, online.size)
        val result = online.last() as ModelItem.ToolResult
        assertEquals(ItemRef("call_1"), result.callRef)
        assertTrue(result.isError)
        assertEquals(INTERRUPTED_TOOL_OUTPUT, result.output)
    }

    @Test
    fun leaves_resolved_calls_untouched() {
        val call = ModelItem.ToolCall(ref = ItemRef("call_1"), name = "search", arguments = "{}")
        val result = ModelItem.ToolResult(callRef = ItemRef("call_1"), output = "ok")
        val stored = listOf(call, result)
        assertEquals(stored, repairTranscript(stored))
    }
}
