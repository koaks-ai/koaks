package org.koaks.framework.loop

import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.memory.TurnStatus
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.TranscriptBasis
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

    @Test
    fun item_added_and_tool_call_completed_do_not_duplicate() {
        val ref = ItemRef("c1")
        val call = ModelItem.ToolCall(ref = ref, name = "get_local_city", arguments = "{}")
        val builder = TurnBuilder("t3", listOf(ModelItem.user("q")))
        builder.observe(ModelEvent.ItemAdded(call))
        builder.observe(
            ModelEvent.ToolCallCompleted(
                org.koaks.framework.model.ToolCall(ref.value, "get_local_city", "{}"),
            ),
        )
        assertEquals(1, builder.snapshot().filterIsInstance<ModelItem.ToolCall>().size)
        assertEquals(1, builder.consumeToolCalls().size)
    }

    @Test
    fun completed_item_replaces_streamed_draft_with_the_same_ref() {
        val ref = ItemRef("msg_1")
        val builder = TurnBuilder("t4", listOf(ModelItem.user("q")))
        builder.observe(ModelEvent.TextDelta("partial", ref))
        builder.observe(
            ModelEvent.ItemAdded(
                ModelItem.assistant("final", ref = ref, refusal = "no"),
            ),
        )

        val messages = builder.snapshot().filterIsInstance<ModelItem.Message>().filter { it.ref == ref }
        assertEquals(1, messages.size)
        assertEquals("partial", messages.single().text)
        assertEquals("no", messages.single().refusal)
    }

    @Test
    fun interrupted_turn_records_partial_text_and_early_checkpoint() {
        val ref = ItemRef("partial_1")
        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId.OpenAIResponses,
            codecVersion = 1,
            basis = TranscriptBasis.of(listOf(ModelItem.user("q"))),
            scope = CheckpointScope.CrossTurn,
            payload = "checkpoint".encodeUtf8(),
        )
        val builder = TurnBuilder("t5", listOf(ModelItem.user("q")))
        builder.observe(ModelEvent.CheckpointUpdated(checkpoint))
        builder.observe(ModelEvent.TextDelta("half", ref))

        val turn = builder.interruptedTurn(org.koaks.framework.memory.InterruptReason.Cancelled)
        val status = turn.status as TurnStatus.Interrupted
        assertEquals("half", status.pending.partialText)
        assertEquals(ref, status.pending.partialItem)
        assertEquals(checkpoint, turn.checkpoint)
        assertEquals("half", turn.items.filterIsInstance<ModelItem.Message>().last().text)
    }

    @Test
    fun usage_accumulates_across_model_calls() {
        val builder = TurnBuilder("t6")
        builder.observe(ModelEvent.Finished(ModelResponse.Completed(usage = Usage(totalTokens = 3))))
        builder.observe(ModelEvent.Finished(ModelResponse.Completed(usage = Usage(totalTokens = 4))))
        assertEquals(7, builder.completedTurn().usage.totalTokens)
    }
}
