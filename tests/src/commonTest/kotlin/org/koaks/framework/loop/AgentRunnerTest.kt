package org.koaks.framework.loop

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunnerTest {

    private fun agentWith(model: FakeLanguageModel, toolsBlock: ToolScope.() -> Unit = {}): Agent =
        agent {
            name = "test"
            model { custom(model) }
            tools(toolsBlock)
            terminateAfter(maxSteps = 5)
        }

    @Test
    fun tee_emits_text_before_tool_call() = runTest {
        // One model step that streams text deltas THEN a completed tool call.
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.TextDelta("Let me "),
                ModelEvent.TextDelta("check. "),
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage(1, 1, 2)),
            ),
            // second step: no tool calls -> finish
            listOf(ModelEvent.TextDelta("Done."), ModelEvent.Completed(Usage(1, 1, 2))),
        )
        val a = agentWith(model) {
            tool<NoArgs>(name = "noop", description = "no-op") { "ok" }
        }

        val events = a.stream("hi").toList()

        val firstTextIdx = events.indexOfFirst { it is AgentEvent.TextDelta }
        val toolReqIdx = events.indexOfFirst { it is AgentEvent.ToolCallRequested }
        assertTrue(firstTextIdx >= 0, "expected a TextDelta")
        assertTrue(toolReqIdx >= 0, "expected a ToolCallRequested")
        assertTrue(firstTextIdx < toolReqIdx, "text must be emitted before the tool call (tee)")
        assertTrue(events.any { it is AgentEvent.Finished })
    }

    @Test
    fun tool_not_found_goes_through_failure_channel() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "ghost", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("recovered"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agentWith(model) // no tools registered

        val events = a.stream("hi").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(toolResult.isError, "tool-not-found must be an error result")
        assertTrue(events.any { it is AgentEvent.Failed }, "a Failed event must be surfaced")
        // And it must NOT be fed back as a normal success string.
        assertTrue(toolResult.output.contains("ghost"))
    }

    @Test
    fun phantom_tool_call_delta_is_not_dispatched() = runTest {
        // A model hallucinates a stray tool_calls fragment at a second index: it arrives
        // only as a ToolCallDelta with no id/name, and the decoder never promotes it to a
        // ToolCallCompleted. The accumulator must drop it rather than dispatch a nameless
        // call (which would surface a fabricated `tool not found: ` error).
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallDelta(id = "c1", index = 0, nameDelta = "noop", argumentsDelta = "{}"),
                ModelEvent.ToolCallDelta(id = "", index = 1), // phantom: blank id, no name
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agentWith(model) {
            tool<NoArgs>(name = "noop", description = "no-op") { "ok" }
        }

        val events = a.stream("hi").toList()

        // Exactly one tool result (the real call); the phantom produced none.
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(1, toolResults.size, "phantom fragment must not be dispatched")
        assertTrue(!toolResults.single().isError)
        // No fabricated tool-not-found failure.
        assertTrue(events.none { it is AgentEvent.Failed }, "phantom must not surface a Failed event")
        assertTrue(events.any { it is AgentEvent.Finished })
    }

    @Test
    fun finishes_when_no_tool_calls() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("hello"), ModelEvent.Completed(Usage(2, 3, 5))),
        )
        val a = agentWith(model)
        val result = a.run("hi")
        assertEquals("hello", result.text)
        assertEquals(5, result.usage.totalTokens)
        assertTrue(result.isSuccess)
    }

    @Test
    fun return_directly_finishes_after_tool() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "answer", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
        )
        val a = agentWith(model) {
            tool<NoArgs>(name = "answer", description = "final", returnDirectly = true) { "the answer" }
        }
        val result = a.run("hi")
        assertEquals("the answer", result.text)
        // Only one model call should have happened (returnDirectly skips the next step).
        assertEquals(1, model.calls)
    }
}
