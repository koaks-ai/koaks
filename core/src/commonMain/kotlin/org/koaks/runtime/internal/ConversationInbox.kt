package org.koaks.runtime.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.koaks.framework.middleware.Hook
import org.koaks.framework.middleware.StepDecision
import org.koaks.framework.middleware.StepHookContext
import org.koaks.framework.middleware.StepPhase
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.coordination.SendResult

/** Separate from request/reply channels: only the loop consumes conversation input. */
internal class ConversationInbox(
    private val capacity: Int,
    private val changed: (Boolean) -> Unit,
    private val report: (String, ModelItem.IncomingMessage, String?) -> Unit,
) {
    private data class State(val open: Boolean = true, val messages: List<ModelItem.IncomingMessage> = emptyList())
    private val state = MutableStateFlow(State())

    fun send(message: ModelItem.IncomingMessage): SendResult {
        while (true) {
            val current = state.value
            if (!current.open) return SendResult.Rejected("target is no longer accepting messages")
            if (current.messages.size >= capacity) return SendResult.Rejected("inbox is full")
            if (state.compareAndSet(current, current.copy(messages = current.messages + message))) {
                report("accepted", message, null)
                return SendResult.Accepted(message.messageId)
            }
        }
    }

    fun finishIfEmpty(): Boolean {
        while (true) {
            val current = state.value
            if (!current.open) return true
            if (current.messages.isNotEmpty()) return false
            if (state.compareAndSet(current, current.copy(open = false))) {
                changed(false)
                return true
            }
        }
    }

    fun close(reason: String = "run ended before processing the message") {
        while (true) {
            val current = state.value
            if (!current.open && current.messages.isEmpty()) return
            if (state.compareAndSet(current, State(open = false))) {
                changed(false)
                current.messages.forEach { report("unprocessed", it, reason) }
                return
            }
        }
    }

    private fun acknowledge(id: Long) {
        while (true) {
            val current = state.value
            if (state.compareAndSet(current, current.copy(messages = current.messages.filterNot { it.messageId == id }))) return
        }
    }

    val injectionHook: Hook = object : Hook {
        override suspend fun onStep(ctx: StepHookContext): StepDecision {
            if (ctx.stage != StepPhase.BeforeModelRequest) return StepDecision.Continue
            // Freeze a batch so concurrent senders cannot starve model admission.
            val batch = state.value.messages
            for (message in batch) {
                withContext(NonCancellable) {
                    ctx.memory.append(message)
                    acknowledge(message.messageId)
                    report("injected", message, null)
                }
            }
            return StepDecision.Continue
        }
    }
}
