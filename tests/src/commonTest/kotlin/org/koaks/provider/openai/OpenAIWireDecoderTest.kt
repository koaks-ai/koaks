package org.koaks.provider.openai

import org.koaks.framework.model.ModelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIWireDecoderTest {

    private fun delta(content: String? = null, reasoning: String? = null, tc: OpenAIChatResponse.ToolCallChunk? = null) =
        OpenAIChatResponse(
            choices = listOf(
                OpenAIChatResponse.Choice(
                    delta = OpenAIChatResponse.Delta(
                        content = content,
                        reasoningContent = reasoning,
                        toolCalls = tc?.let { listOf(it) },
                    )
                )
            )
        )

    @Test
    fun assembles_tool_call_across_chunks() {
        val decoder = OpenAIWireDecoder()
        val events = buildList {
            addAll(decoder.accept(delta(content = "thinking ")))
            // name + arguments arrive split across several chunks, all at index 0.
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 0, id = "call_1", function = OpenAIChatResponse.FunctionChunk(name = "get_")))))
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 0, function = OpenAIChatResponse.FunctionChunk(name = "weather")))))
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 0, function = OpenAIChatResponse.FunctionChunk(arguments = "{\"city\":")))))
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 0, function = OpenAIChatResponse.FunctionChunk(arguments = "\"NYC\"}")))))
            addAll(decoder.accept(OpenAIChatResponse(usage = OpenAIChatResponse.Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15))))
            addAll(decoder.finish())
        }

        // Text was forwarded as a delta.
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "thinking " })

        // A single completed tool call with assembled name + arguments.
        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertEquals("get_weather", completed.call.name)
        assertEquals("{\"city\":\"NYC\"}", completed.call.arguments)

        // Usage surfaced on completion.
        val done = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals(15, done.usage.totalTokens)
    }

    @Test
    fun forwards_reasoning_before_content_as_distinct_events() {
        val decoder = OpenAIWireDecoder()
        val events = buildList {
            addAll(decoder.accept(delta(reasoning = "let me ")))
            addAll(decoder.accept(delta(reasoning = "think")))
            addAll(decoder.accept(delta(content = "the answer")))
            addAll(decoder.finish())
        }

        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>()
        assertEquals(listOf("let me ", "think"), reasoning.map { it.text })

        // Reasoning is NOT conflated with assistant text.
        val text = events.filterIsInstance<ModelEvent.TextDelta>().single()
        assertEquals("the answer", text.text)
    }

    @Test
    fun reports_error_chunk_as_failed() {
        val decoder = OpenAIWireDecoder()
        val events = decoder.accept(
            OpenAIChatResponse(error = OpenAIChatResponse.ErrorOutput(message = "bad key", code = "401"))
        )
        assertTrue(events.single() is ModelEvent.Failed)
    }

    @Test
    fun assembles_parallel_tool_calls_in_index_order() {
        val decoder = OpenAIWireDecoder()
        buildList {
            // Two parallel calls whose fragments interleave; index 1 even arrives before index 0.
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 1, id = "call_b", function = OpenAIChatResponse.FunctionChunk(name = "second")))))
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 0, id = "call_a", function = OpenAIChatResponse.FunctionChunk(name = "first")))))
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 0, function = OpenAIChatResponse.FunctionChunk(arguments = "{}")))))
            addAll(decoder.accept(delta(tc = OpenAIChatResponse.ToolCallChunk(index = 1, function = OpenAIChatResponse.FunctionChunk(arguments = "{}")))))
        }
        val completed = decoder.finish().filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(2, completed.size)
        // Emitted sorted by index regardless of arrival order.
        assertEquals("first", completed[0].call.name)
        assertEquals("call_a", completed[0].call.id)
        assertEquals("second", completed[1].call.name)
        assertEquals("call_b", completed[1].call.id)
    }
}
