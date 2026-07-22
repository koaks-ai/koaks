package org.koaks.framework.memory

import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.model.Message
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall

/**
 * Accumulates the messages produced during a single run by observing the outward
 * [AgentEvent] stream, so the loop itself never has to know about [ThreadMemory].
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

    fun observe(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> pendingText.append(event.text)
            // Reasoning is a transient trace, never persisted to history.
            is AgentEvent.ReasoningDelta -> {}
            is AgentEvent.ToolCallRequested -> pendingCalls += event.call
            is AgentEvent.ToolResult -> {
                pendingToolResults += Message.tool(event.callId, event.output, event.isError)
            }

            is AgentEvent.StepCompleted -> flushStep()
            is AgentEvent.Terminal -> {
                // Flush any half-assembled step BEFORE marking terminal: a terminal can
                // arrive without a trailing StepCompleted (a `returnDirectly` tool step, or a
                // quota preemption mid-step), and its pending assistant/tool-result messages
                // must not be silently dropped — otherwise we could commit an assistant
                // tool-call with no matching tool result (providers reject that).
                flushStep()
                // `returnDirectly` finishes by emitting a synthetic assistant message that
                // never appeared as text deltas or a StepCompleted, so it is not yet in
                // `committed`. Every normal/policy terminal is preceded by a model step whose
                // assistant was just flushed (last message is ASSISTANT); only a returnDirectly
                // terminal lands right after its tool results (last message is TOOL). Append the
                // terminal's message in exactly that case, so the final answer is not lost.
                val message = event.message
                if (message.text.isNotEmpty() && committed.lastOrNull()?.role != Role.ASSISTANT) {
                    committed += message
                }
                sawTerminal = true
            }
            is AgentEvent.Failed -> {} // non-terminal failures may still be followed by a terminal event
        }
    }

    /** Closes out the current model step into an assistant message + its tool results. */
    private fun flushStep() {
        val text = pendingText.toString()
        // Tool results arrive after the tool-calling step has already emitted
        // StepCompleted, so they belong before the assistant message assembled for the
        // next model step. Keeping this order is required by OpenAI-compatible APIs:
        // every assistant tool_call must be followed immediately by its tool result.
        committed += pendingToolResults
        if (text.isNotEmpty() || pendingCalls.isNotEmpty()) {
            committed += Message.assistant(text, pendingCalls.toList())
        }
        pendingText.clear()
        pendingCalls.clear()
        pendingToolResults.clear()
    }

    /** True only if the run reached a non-error terminal event — the gate for committing. */
    fun shouldCommit(): Boolean = sawTerminal

    /** The full ordered set of new messages for this run (user + assistant + tool). */
    fun messagesInOrder(): List<Message> = committed.toList()

}
