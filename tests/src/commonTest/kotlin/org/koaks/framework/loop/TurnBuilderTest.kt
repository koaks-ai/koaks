package org.koaks.framework.loop

import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnBuilderTest {

    @Test
    fun completed_turn_includes_seed_and_assistant_text() {
        val builder = TurnBuilder("t1", listOf(ModelItem.user("q")))
        builder.observe(ModelEvent.TextDelta("hello"))
        builder.observe(ModelEvent.Finished(org.koaks.framework.model.ModelResponse.Completed(usage = Usage.ZERO)))
        val turn = builder.completedTurn()
        assertEquals("t1", turn.id)
        assertTrue(turn.items.any { it is ModelItem.Message && it.text == "q" })
        assertTrue(turn.items.any { it is ModelItem.Message && it.text == "hello" })
    }

    @Test
    fun interrupted_turn_keeps_orphan_tool_call() {
        val builder = TurnBuilder("t2", listOf(ModelItem.user("create a file")))
        builder.observe(
            ModelEvent.ToolCallCompleted(
                org.koaks.framework.model.ToolCall("c1", "write", "{}"),
            ),
        )
        val turn = builder.interruptedTurn(org.koaks.framework.memory.InterruptReason.Cancelled)
        assertTrue(turn.status is org.koaks.framework.memory.TurnStatus.Interrupted)
        assertEquals(listOf(ItemRef("c1")), (turn.status as org.koaks.framework.memory.TurnStatus.Interrupted).pending.unresolvedCalls)
    }
}
