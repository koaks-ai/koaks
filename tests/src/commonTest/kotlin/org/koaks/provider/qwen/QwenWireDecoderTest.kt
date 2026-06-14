package org.koaks.provider.qwen

import org.koaks.framework.model.ModelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QwenWireDecoderTest {

    private fun delta(content: String? = null, tc: QwenChatResponse.ToolCallChunk? = null) =
        QwenChatResponse(
            choices = listOf(
                QwenChatResponse.Choice(
                    delta = QwenChatResponse.Delta(
                        content = content,
                        toolCalls = tc?.let { listOf(it) },
                    )
                )
            )
        )

    @Test
    fun assembles_tool_call_across_chunks() {
        val decoder = QwenWireDecoder()
        val events = buildList {
            addAll(decoder.accept(delta(content = "thinking ")))
            // name + arguments arrive split across several chunks, all at index 0.
            addAll(decoder.accept(delta(tc = QwenChatResponse.ToolCallChunk(index = 0, id = "call_1", function = QwenChatResponse.FunctionChunk(name = "get_")))))
            addAll(decoder.accept(delta(tc = QwenChatResponse.ToolCallChunk(index = 0, function = QwenChatResponse.FunctionChunk(name = "weather")))))
            addAll(decoder.accept(delta(tc = QwenChatResponse.ToolCallChunk(index = 0, function = QwenChatResponse.FunctionChunk(arguments = "{\"city\":")))))
            addAll(decoder.accept(delta(tc = QwenChatResponse.ToolCallChunk(index = 0, function = QwenChatResponse.FunctionChunk(arguments = "\"NYC\"}")))))
            addAll(decoder.accept(QwenChatResponse(usage = QwenChatResponse.Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15))))
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
    fun reports_error_chunk_as_failed() {
        val decoder = QwenWireDecoder()
        val events = decoder.accept(
            QwenChatResponse(error = QwenChatResponse.ErrorOutput(message = "bad key", code = "401"))
        )
        assertTrue(events.single() is ModelEvent.Failed)
    }
}
