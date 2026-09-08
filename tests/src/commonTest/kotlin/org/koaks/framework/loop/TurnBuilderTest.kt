package org.koaks.framework.loop

import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnBuilderTest {

    @Test
    fun finished_step_includes_streamed_assistant_text() {
        val builder = TurnBuilder()
        builder.observe(ModelEvent.TextDelta("hello"))
        builder.observe(ModelEvent.Finished(org.koaks.framework.model.ModelResponse.Completed(usage = Usage.ZERO)))
        assertTrue(builder.takeStepItems().any { it is ModelItem.Message && it.text == "hello" })
    }

    @Test
    fun interrupted_step_keeps_orphan_tool_call() {
        val builder = TurnBuilder()
        builder.observe(
            ModelEvent.ToolCallCompleted(
                org.koaks.framework.model.ToolCall("c1", "write", "{}"),
            ),
        )
        assertEquals(listOf("write"), builder.toolCalls().map { it.name })
        assertEquals(listOf(ItemRef("c1")), builder.takeStepItems().filterIsInstance<ModelItem.ToolCall>().map { it.ref })
    }

    @Test
    fun item_added_and_tool_call_completed_do_not_duplicate() {
        val ref = ItemRef("c1")
        val call = ModelItem.ToolCall(ref = ref, name = "get_local_city", arguments = "{}")
        val builder = TurnBuilder()
        builder.observe(ModelEvent.ItemAdded(call))
        builder.observe(
            ModelEvent.ToolCallCompleted(
                org.koaks.framework.model.ToolCall(ref.value, "get_local_city", "{}"),
            ),
        )
        assertEquals(1, builder.snapshot().filterIsInstance<ModelItem.ToolCall>().size)
        assertEquals(1, builder.toolCalls().size)
    }

    @Test
    fun completed_item_replaces_streamed_draft_with_the_same_ref() {
        val ref = ItemRef("msg_1")
        val builder = TurnBuilder()
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
    fun flush_partial_records_streamed_text_and_keeps_checkpoint() {
        val ref = ItemRef("partial_1")
        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId.OpenAIResponses,
            codecVersion = 1,
            basis = TranscriptBasis.of(listOf(ModelItem.user("q"))),
            scope = CheckpointScope.CrossTurn,
            payload = "checkpoint".encodeUtf8(),
        )
        val builder = TurnBuilder()
        builder.observe(ModelEvent.CheckpointUpdated(checkpoint))
        builder.observe(ModelEvent.TextDelta("half", ref))

        val partial = builder.flushPartial()
        assertEquals("half", (partial as ModelItem.Message).text)
        assertEquals(ref, partial.ref)
        assertEquals(checkpoint, builder.lastCheckpoint())
    }

    @Test
    fun last_response_usage_is_the_finished_event() {
        val builder = TurnBuilder()
        builder.observe(ModelEvent.Finished(org.koaks.framework.model.ModelResponse.Completed(usage = Usage(totalTokens = 3))))
        builder.reset()
        builder.observe(ModelEvent.Finished(org.koaks.framework.model.ModelResponse.Completed(usage = Usage(totalTokens = 4))))
        assertEquals(4, builder.lastResponse()?.usage?.totalTokens)
    }
}
