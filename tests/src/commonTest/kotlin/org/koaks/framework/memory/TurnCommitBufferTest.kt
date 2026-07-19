package org.koaks.framework.memory

import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.model.Message
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnCommitBufferTest {

    @Test
    fun normal_completion_does_not_duplicate_the_final_assistant() {
        val buffer = TurnCommitBuffer(Message.user("q"))
        buffer.observe(AgentEvent.TextDelta("hello"))
        buffer.observe(AgentEvent.StepCompleted(1))
        buffer.observe(AgentEvent.Completed(Message.assistant("hello"), Usage.ZERO))

        assertTrue(buffer.shouldCommit())
        val roles = buffer.messagesInOrder().map { it.role }
        // Exactly user + one assistant; the Completed terminal must NOT re-append it.
        assertEquals(listOf(Role.USER, Role.ASSISTANT), roles)
        assertEquals(1, buffer.messagesInOrder().count { it.role == Role.ASSISTANT })
    }

    @Test
    fun return_directly_commits_full_sequence_without_orphaning_tool_call() {
        // A returnDirectly turn: model step requests a tool, tool result arrives, then the loop
        // emits Completed(assistant(output)) with NO trailing StepCompleted.
        val call = ToolCall(id = "c1", name = "lookup", arguments = "{}")
        val buffer = TurnCommitBuffer(Message.user("q"))
        buffer.observe(AgentEvent.ToolCallRequested(call))
        buffer.observe(AgentEvent.StepCompleted(1))              // flushes assistant(tool_call)
        buffer.observe(AgentEvent.ToolResult("c1", "42", isError = false))
        buffer.observe(AgentEvent.Completed(Message.assistant("the answer is 42"), Usage.ZERO))

        assertTrue(buffer.shouldCommit())
        val messages = buffer.messagesInOrder()
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT),
            messages.map { it.role },
        )
        // The assistant tool_call is paired with its tool result (no orphan → provider-safe).
        assertTrue(messages[1].toolCalls.any { it.id == "c1" })
        assertEquals("42", (messages[2].parts.first() as org.koaks.framework.model.ContentPart.ToolResultPart).output)
        // The final synthetic answer is preserved.
        assertEquals("the answer is 42", messages[3].text)
    }

    @Test
    fun terminal_flushes_a_half_assembled_step() {
        // A terminal arriving mid-step (e.g. a quota preemption) must still flush what was
        // assembled so far rather than dropping it.
        val call = ToolCall(id = "c9", name = "t", arguments = "{}")
        val buffer = TurnCommitBuffer(Message.user("q"))
        buffer.observe(AgentEvent.TextDelta("partial"))
        buffer.observe(AgentEvent.ToolCallRequested(call))
        buffer.observe(
            AgentEvent.Terminated(
                Message.assistant("partial"),
                Usage.ZERO,
                TerminationReason.Custom("quota exceeded: maxToolCalls=0"),
            ),
        )

        val messages = buffer.messagesInOrder()
        // user + the flushed in-flight assistant (text + call); nothing silently dropped.
        assertEquals(listOf(Role.USER, Role.ASSISTANT), messages.map { it.role })
        assertEquals("partial", messages[1].text)
        assertTrue(messages[1].toolCalls.any { it.id == "c9" })
    }
}
