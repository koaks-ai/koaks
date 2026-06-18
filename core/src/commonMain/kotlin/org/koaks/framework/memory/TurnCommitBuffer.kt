package org.koaks.framework.memory

import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.model.Message
import org.koaks.framework.model.ToolCall

/**
 * Accumulates the messages produced during a single run by observing the outward
 * [AgentEvent] stream, so the loop itself never has to know about [Memory].
 *
 * The buffer reconstructs, in order: the user message (seeded), then for each model
 * step an assistant message (text + any requested tool calls) followed by its tool
 * result messages. The whole buffer is committed atomically — and ONLY if the run
 * reached [AgentEvent.Terminal]. A failed/cancelled run discards it untouched, so a
 * half-finished turn never pollutes persistent history.
 */
class TurnCommitBuffer(userMessage: Message) {

    private val committed = mutableListOf(userMessage)

    // In-flight assistant turn being assembled from deltas.
    private val pendingText = StringBuilder()
    private val pendingCalls = mutableListOf<ToolCall>()
    private val pendingToolResults = mutableListOf<Message>()
    private var sawTerminal = false
    private var anyToolResult = false

    fun observe(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> pendingText.append(event.text)
            // Reasoning is a transient trace, never persisted to history.
            is AgentEvent.ReasoningDelta -> {}
            is AgentEvent.ToolCallRequested -> pendingCalls += event.call
            is AgentEvent.ToolResult -> {
                anyToolResult = true
                pendingToolResults += Message.tool(event.callId, event.output, event.isError)
            }

            is AgentEvent.StepCompleted -> flushStep()
            is AgentEvent.Terminal -> sawTerminal = true
            is AgentEvent.Failed -> {} // non-terminal failures may still be followed by a terminal event
        }
    }

    /** Closes out the current model step into an assistant message + its tool results. */
    private fun flushStep() {
        val text = pendingText.toString()
        if (text.isNotEmpty() || pendingCalls.isNotEmpty()) {
            committed += Message.assistant(text, pendingCalls.toList())
        }
        committed += pendingToolResults
        pendingText.clear()
        pendingCalls.clear()
        pendingToolResults.clear()
    }

    /** True only if the run reached a non-error terminal event — the gate for committing. */
    fun shouldCommit(): Boolean = sawTerminal

    /** The full ordered set of new messages for this run (user + assistant + tool). */
    fun messagesInOrder(): List<Message> = committed.toList()

    /** True if any tool produced a result during this run (for the side-effect rollback warning). */
    fun producedToolResults(): Boolean = anyToolResult
}
