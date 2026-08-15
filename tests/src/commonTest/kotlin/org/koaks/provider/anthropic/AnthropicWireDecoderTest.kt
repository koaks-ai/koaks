package org.koaks.provider.anthropic

import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
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
            addAll(decoder.acceptChunk(messageStart(inputTokens = 10)))
            addAll(decoder.acceptChunk(toolUseStart(index = 0, id = "toolu_1", name = "get_weather")))
            // The input object arrives split across several input_json_delta fragments.
            addAll(decoder.acceptChunk(inputJsonDelta(index = 0, partial = "{\"city\":")))
            addAll(decoder.acceptChunk(inputJsonDelta(index = 0, partial = "\"NYC\"}")))
            addAll(decoder.acceptChunk(messageDelta(outputTokens = 5)))
            addAll(decoder.finish())
        }

        // A single completed tool call with assembled name + arguments.
        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertEquals("get_weather", completed.call.name)
        assertEquals("toolu_1", completed.call.nativeId?.raw)
        assertEquals("{\"city\":\"NYC\"}", completed.call.arguments)

        val done = events.filterIsInstance<ModelEvent.Finished>().single()
        assertEquals(10, done.response.usage.promptTokens)
        assertEquals(5, done.response.usage.completionTokens)
        assertEquals(15, done.response.usage.totalTokens)
    }

    @Test
    fun forwards_text_and_thinking_as_distinct_events() {
        val decoder = AnthropicWireDecoder()
        val events = buildList {
            addAll(decoder.acceptChunk(messageStart(inputTokens = 3)))
            addAll(decoder.acceptChunk(thinkingDelta("let me ")))
            addAll(decoder.acceptChunk(thinkingDelta("think")))
            addAll(decoder.acceptChunk(textDelta("the answer")))
            addAll(decoder.acceptChunk(messageDelta(outputTokens = 7)))
            addAll(decoder.finish())
        }

        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>()
        assertEquals(listOf("let me ", "think"), reasoning.map { it.text })

        // Reasoning is NOT conflated with assistant text.
        val text = events.filterIsInstance<ModelEvent.TextDelta>().single()
        assertEquals("the answer", text.text)

        val done = events.filterIsInstance<ModelEvent.Finished>().single()
        assertEquals(10, done.response.usage.totalTokens)
    }

    @Test
    fun reports_error_chunk_as_failed() {
        val decoder = AnthropicWireDecoder()
        val events = decoder.acceptChunk(
            AnthropicChatResponse(
                type = "error",
                error = AnthropicChatResponse.ErrorOutput(type = "authentication_error", message = "bad key"),
            )
        )
        val finished = events.filterIsInstance<ModelEvent.Finished>().single()
        assertTrue(finished.response is org.koaks.framework.model.ModelResponse.Failed)
    }

    @Test
    fun assembles_parallel_tool_calls_in_index_order() {
        val decoder = AnthropicWireDecoder()
        buildList {
            addAll(decoder.acceptChunk(messageStart(inputTokens = 1)))
            // Two tool_use blocks at distinct indices.
            addAll(decoder.acceptChunk(toolUseStart(index = 0, id = "toolu_a", name = "first")))
            addAll(decoder.acceptChunk(inputJsonDelta(index = 0, partial = "{}")))
            addAll(decoder.acceptChunk(toolUseStart(index = 1, id = "toolu_b", name = "second")))
            addAll(decoder.acceptChunk(inputJsonDelta(index = 1, partial = "{}")))
        }
        val completed = decoder.finish().filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(2, completed.size)
        // Emitted sorted by content-block index.
        assertEquals("first", completed[0].call.name)
        assertEquals("toolu_a", completed[0].call.nativeId?.raw)
        assertEquals("second", completed[1].call.name)
        assertEquals("toolu_b", completed[1].call.nativeId?.raw)
    }

    @Test
    fun concatenates_fragmented_thinking_signature_for_exact_replay() {
        val decoder = AnthropicWireDecoder()
        val events = buildList {
            addAll(
                decoder.acceptChunk(
                    AnthropicChatResponse(
                        type = "content_block_start",
                        index = 0,
                        contentBlock = AnthropicChatResponse.ContentBlock(type = "thinking"),
                    ),
                ),
            )
            addAll(decoder.acceptChunk(thinkingDelta("reason")))
            addAll(
                decoder.acceptChunk(
                    AnthropicChatResponse(
                        type = "content_block_delta",
                        index = 0,
                        delta = AnthropicChatResponse.Delta(type = "signature_delta", signature = "sig-"),
                    ),
                ),
            )
            addAll(
                decoder.acceptChunk(
                    AnthropicChatResponse(
                        type = "content_block_delta",
                        index = 0,
                        delta = AnthropicChatResponse.Delta(type = "signature_delta", signature = "tail"),
                    ),
                ),
            )
            addAll(decoder.acceptChunk(AnthropicChatResponse(type = "content_block_stop", index = 0)))
            addAll(decoder.finish())
        }

        val providerItem = events.filterIsInstance<ModelEvent.ItemAdded>()
            .map { it.item }
            .filterIsInstance<ModelItem.ProviderItem>()
            .single()
        val replay = toAnthropicMessages(listOf(ModelItem.user("q"), providerItem))
        val thinking = replay.last().content.filterIsInstance<AnthropicContentBlock.Thinking>().single()
        assertEquals("reason", thinking.thinking)
        assertEquals("sig-tail", thinking.signature)
    }
}
