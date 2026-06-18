package org.koaks.provider.anthropic

import org.koaks.framework.model.ModelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicWireDecoderTest {

    private fun messageStart(inputTokens: Int) = AnthropicChatResponse(
        type = "message_start",
        message = AnthropicChatResponse.Message(usage = AnthropicChatResponse.Usage(inputTokens = inputTokens)),
    )

    private fun messageDelta(outputTokens: Int) = AnthropicChatResponse(
        type = "message_delta",
        usage = AnthropicChatResponse.Usage(outputTokens = outputTokens),
    )

    private fun toolUseStart(index: Int, id: String, name: String) = AnthropicChatResponse(
        type = "content_block_start",
        index = index,
        contentBlock = AnthropicChatResponse.ContentBlock(type = "tool_use", id = id, name = name),
    )

    private fun inputJsonDelta(index: Int, partial: String) = AnthropicChatResponse(
        type = "content_block_delta",
        index = index,
        delta = AnthropicChatResponse.Delta(type = "input_json_delta", partialJson = partial),
    )

    private fun textDelta(text: String) = AnthropicChatResponse(
        type = "content_block_delta",
        index = 0,
        delta = AnthropicChatResponse.Delta(type = "text_delta", text = text),
    )

    private fun thinkingDelta(text: String) = AnthropicChatResponse(
        type = "content_block_delta",
        index = 0,
        delta = AnthropicChatResponse.Delta(type = "thinking_delta", thinking = text),
    )

    @Test
    fun assembles_tool_call_across_chunks() {
        val decoder = AnthropicWireDecoder()
        val events = buildList {
            addAll(decoder.accept(messageStart(inputTokens = 10)))
            addAll(decoder.accept(toolUseStart(index = 0, id = "toolu_1", name = "get_weather")))
            // The input object arrives split across several input_json_delta fragments.
            addAll(decoder.accept(inputJsonDelta(index = 0, partial = "{\"city\":")))
            addAll(decoder.accept(inputJsonDelta(index = 0, partial = "\"NYC\"}")))
            addAll(decoder.accept(messageDelta(outputTokens = 5)))
            addAll(decoder.finish())
        }

        // A single completed tool call with assembled name + arguments.
        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertEquals("get_weather", completed.call.name)
        assertEquals("toolu_1", completed.call.id)
        assertEquals("{\"city\":\"NYC\"}", completed.call.arguments)

        // Usage: prompt from message_start, completion from message_delta.
        val done = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals(10, done.usage.promptTokens)
        assertEquals(5, done.usage.completionTokens)
        assertEquals(15, done.usage.totalTokens)
    }

    @Test
    fun forwards_text_and_thinking_as_distinct_events() {
        val decoder = AnthropicWireDecoder()
        val events = buildList {
            addAll(decoder.accept(messageStart(inputTokens = 3)))
            addAll(decoder.accept(thinkingDelta("let me ")))
            addAll(decoder.accept(thinkingDelta("think")))
            addAll(decoder.accept(textDelta("the answer")))
            addAll(decoder.accept(messageDelta(outputTokens = 7)))
            addAll(decoder.finish())
        }

        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>()
        assertEquals(listOf("let me ", "think"), reasoning.map { it.text })

        // Reasoning is NOT conflated with assistant text.
        val text = events.filterIsInstance<ModelEvent.TextDelta>().single()
        assertEquals("the answer", text.text)

        val done = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals(10, done.usage.totalTokens)
    }

    @Test
    fun reports_error_chunk_as_failed() {
        val decoder = AnthropicWireDecoder()
        val events = decoder.accept(
            AnthropicChatResponse(
                type = "error",
                error = AnthropicChatResponse.ErrorOutput(type = "authentication_error", message = "bad key"),
            )
        )
        assertTrue(events.single() is ModelEvent.Failed)
    }

    @Test
    fun assembles_parallel_tool_calls_in_index_order() {
        val decoder = AnthropicWireDecoder()
        buildList {
            addAll(decoder.accept(messageStart(inputTokens = 1)))
            // Two tool_use blocks at distinct indices.
            addAll(decoder.accept(toolUseStart(index = 0, id = "toolu_a", name = "first")))
            addAll(decoder.accept(inputJsonDelta(index = 0, partial = "{}")))
            addAll(decoder.accept(toolUseStart(index = 1, id = "toolu_b", name = "second")))
            addAll(decoder.accept(inputJsonDelta(index = 1, partial = "{}")))
        }
        val completed = decoder.finish().filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(2, completed.size)
        // Emitted sorted by content-block index.
        assertEquals("first", completed[0].call.name)
        assertEquals("toolu_a", completed[0].call.id)
        assertEquals("second", completed[1].call.name)
        assertEquals("toolu_b", completed[1].call.id)
    }
}
