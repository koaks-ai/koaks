package org.koaks.provider.chatcompletions

import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderId
import org.koaks.framework.transport.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatCompletionsDecoderTest {

    private fun decoder() = ChatCompletionsDecoder(ProviderId.OpenAI)

    private fun delta(
        content: String? = null,
        reasoning: String? = null,
        tc: ChatCompletionsResponse.ToolCallChunk? = null,
    ) = ChatCompletionsResponse(
        choices = listOf(
            ChatCompletionsResponse.Choice(
                delta = ChatCompletionsResponse.Delta(
                    content = content,
                    reasoningContent = reasoning,
                    toolCalls = tc?.let { listOf(it) },
                ),
            ),
        ),
    )

    @Test
    fun assembles_tool_call_across_chunks() {
        val decoder = decoder()
        val events = buildList {
            addAll(decoder.acceptChunk(delta(content = "thinking ")))
            addAll(decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 0, id = "call_1", function = ChatCompletionsResponse.FunctionChunk(name = "get_")))))
            addAll(decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 0, function = ChatCompletionsResponse.FunctionChunk(name = "weather")))))
            addAll(decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 0, function = ChatCompletionsResponse.FunctionChunk(arguments = "{\"city\":")))))
            addAll(decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 0, function = ChatCompletionsResponse.FunctionChunk(arguments = "\"NYC\"}")))))
            addAll(decoder.acceptChunk(ChatCompletionsResponse(usage = ChatCompletionsResponse.Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15))))
            addAll(decoder.finish())
        }

        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "thinking " })
        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertEquals("get_weather", completed.call.name)
        assertEquals("{\"city\":\"NYC\"}", completed.call.arguments)
        assertEquals("call_1", completed.call.nativeId?.raw)

        val done = events.filterIsInstance<ModelEvent.Finished>().single()
        assertEquals(15, done.response.usage.totalTokens)
    }

    @Test
    fun forwards_reasoning_before_content_as_distinct_events() {
        val decoder = decoder()
        val events = buildList {
            addAll(decoder.acceptChunk(delta(reasoning = "let me ")))
            addAll(decoder.acceptChunk(delta(reasoning = "think")))
            addAll(decoder.acceptChunk(delta(content = "the answer")))
            addAll(decoder.finish())
        }
        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>()
        assertEquals(listOf("let me ", "think"), reasoning.map { it.text })
        assertTrue(reasoning.all { it.kind == ModelEvent.ReasoningKind.RAW })
        assertEquals(1, reasoning.map { it.itemRef }.toSet().size)
        val text = events.filterIsInstance<ModelEvent.TextDelta>().single()
        assertEquals("the answer", text.text)
    }

    @Test
    fun lossless_preserves_chunks_done_and_malformed_payloads() {
        val decoder = ChatCompletionsDecoder(ProviderId.OpenAI, EventDetail.LOSSLESS)
        val frames = listOf(
            WireFrame.Sse(
                null,
                """{"object":"chat.completion.chunk","id":"chat_1","choices":[{"index":0,"delta":{"content":"hi"}},{"index":1,"delta":{"content":"kept only in raw"}}]}""",
                "evt-1",
            ),
            WireFrame.Sse(null, "{broken", null),
            WireFrame.Sse(null, "[DONE]", null),
        )

        val events = frames.flatMap(decoder::accept)
        val raw = events.filterIsInstance<ModelEvent.ProviderEvent>()

        assertEquals(frames.map { (it as WireFrame.Sse).data }, raw.map { it.payload })
        assertEquals(listOf("chat.completion.chunk", "chat.completion.chunk", "done"), raw.map { it.type })
        assertTrue(raw.all { it.protocolId == ProtocolId.ChatCompletions })
        assertEquals("evt-1", raw.first().eventId)
        assertTrue(events.indexOf(raw.first()) < events.indexOfFirst { it is ModelEvent.Started })
        assertEquals(listOf("hi"), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertTrue(raw.first().payload.contains("kept only in raw"))
    }

    @Test
    fun refusal_delta_is_projected_and_preserved_in_the_message() {
        val decoder = decoder()
        val events = buildList {
            addAll(
                decoder.acceptChunk(
                    ChatCompletionsResponse(
                        choices = listOf(
                            ChatCompletionsResponse.Choice(
                                delta = ChatCompletionsResponse.Delta(refusal = "cannot comply"),
                            ),
                        ),
                    ),
                ),
            )
            addAll(decoder.finish())
        }

        val refusal = events.filterIsInstance<ModelEvent.RefusalDelta>().single()
        val message = events.filterIsInstance<ModelEvent.Finished>().single().response.output
            .filterIsInstance<org.koaks.framework.model.ModelItem.Message>().single()
        assertEquals(refusal.itemRef, message.ref)
        assertEquals("cannot comply", message.refusal)
    }

    @Test
    fun reports_error_chunk_as_failed() {
        val decoder = decoder()
        val events = decoder.acceptChunk(
            ChatCompletionsResponse(error = ChatCompletionsResponse.ErrorOutput(message = "bad key", code = "401")),
        )
        val finished = events.filterIsInstance<ModelEvent.Finished>().single()
        assertTrue(finished.response is ModelResponse.Failed)
    }

    @Test
    fun assembles_parallel_tool_calls_in_index_order() {
        val decoder = decoder()
        decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 1, id = "call_b", function = ChatCompletionsResponse.FunctionChunk(name = "second"))))
        decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 0, id = "call_a", function = ChatCompletionsResponse.FunctionChunk(name = "first"))))
        decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 0, function = ChatCompletionsResponse.FunctionChunk(arguments = "{}"))))
        decoder.acceptChunk(delta(tc = ChatCompletionsResponse.ToolCallChunk(index = 1, function = ChatCompletionsResponse.FunctionChunk(arguments = "{}"))))
        val completed = decoder.finish().filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(2, completed.size)
        assertEquals("first", completed[0].call.name)
        assertEquals("call_a", completed[0].call.nativeId?.raw)
        assertEquals("second", completed[1].call.name)
        assertEquals("call_b", completed[1].call.nativeId?.raw)
    }
}
