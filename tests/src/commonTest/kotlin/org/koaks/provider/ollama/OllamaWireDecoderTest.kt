package org.koaks.provider.ollama

import org.koaks.framework.model.ModelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaWireDecoderTest {

    private fun chunk(
        content: String = "",
        thinking: String? = null,
        toolCalls: List<OllamaRespToolCall>? = null,
        done: Boolean = false,
        promptEval: Int? = null,
        eval: Int? = null,
    ) = OllamaChatResponse(
        model = "llama3.1",
        message = OllamaRespMessage(role = "assistant", content = content, thinking = thinking, toolCalls = toolCalls),
        done = done,
        promptEvalCount = promptEval,
        evalCount = eval,
    )

    @Test
    fun forwards_content_deltas_and_usage() {
        val decoder = OllamaWireDecoder()
        val events = buildList {
            addAll(decoder.accept(chunk(content = "Hello ")))
            addAll(decoder.accept(chunk(content = "world")))
            addAll(decoder.accept(chunk(done = true, promptEval = 8, eval = 4)))
            addAll(decoder.finish())
        }

        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello world", text)

        val done = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals(12, done.usage.totalTokens)
    }

    @Test
    fun assembles_complete_tool_call_with_synthetic_id() {
        val decoder = OllamaWireDecoder()
        val tc = OllamaRespToolCall(
            function = OllamaRespFunction(
                name = "get_weather",
                arguments = kotlinx.serialization.json.buildJsonObject {
                    put("city", kotlinx.serialization.json.JsonPrimitive("NYC"))
                },
            )
        )
        val events = buildList {
            addAll(decoder.accept(chunk(toolCalls = listOf(tc))))
            addAll(decoder.accept(chunk(done = true, eval = 3)))
            addAll(decoder.finish())
        }

        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertEquals("get_weather", completed.call.name)
        assertEquals("call_0", completed.call.id)
        assertTrue(completed.call.arguments.contains("\"city\""))
        assertTrue(completed.call.arguments.contains("NYC"))
    }

    @Test
    fun forwards_thinking_as_reasoning_distinct_from_content() {
        val decoder = OllamaWireDecoder()
        val events = buildList {
            addAll(decoder.accept(chunk(thinking = "hmm ")))
            addAll(decoder.accept(chunk(thinking = "ok", content = "answer")))
            addAll(decoder.accept(chunk(done = true, eval = 2)))
            addAll(decoder.finish())
        }

        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>().joinToString("") { it.text }
        assertEquals("hmm ok", reasoning)

        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("answer", text)
    }

    @Test
    fun reports_error_as_failed() {
        val decoder = OllamaWireDecoder()
        val events = decoder.accept(OllamaChatResponse(error = "model not found"))
        assertTrue(events.single() is ModelEvent.Failed)
    }
}
