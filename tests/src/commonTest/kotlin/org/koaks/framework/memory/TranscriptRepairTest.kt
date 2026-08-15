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

    @Test
    fun inserts_orphan_result_before_a_later_turn() {
        val call = ModelItem.ToolCall(ref = ItemRef("call_1"), name = "search", arguments = "{}")
        val laterUser = ModelItem.user("next")
        val stored = listOf(ModelItem.user("q"), call, laterUser, ModelItem.assistant("answer"))

        val online = repairTranscript(stored)

        assertEquals(call, online[1])
        val result = online[2] as ModelItem.ToolResult
        assertEquals(call.ref, result.callRef)
        assertEquals(ItemRef("repair_call_1"), result.ref)
        assertEquals(laterUser, online[3])
        assertEquals(4, stored.size, "storage must remain untouched")
    }

    @Test
    fun inserts_all_parallel_orphan_results_before_the_next_message() {
        val first = ModelItem.ToolCall(ref = ItemRef("c1"), name = "a", arguments = "{}")
        val second = ModelItem.ToolCall(ref = ItemRef("c2"), name = "b", arguments = "{}")
        val next = ModelItem.assistant("continued")

        val online = repairTranscript(listOf(first, second, next))

        assertEquals(listOf(ItemRef("c1"), ItemRef("c2")), online.filterIsInstance<ModelItem.ToolResult>().map { it.callRef })
        assertEquals(next, online.last())
    }
}
