package org.koaks.framework.middleware

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.ModelEvent

/**
 * Tracing implemented as an observe-only [AgentListener] (not `aroundModelCall`),
 * so it can never double-subscribe the model flow. `install(Tracing)` wires it in.
 */
object Tracing : AgentListener {
    private val logger = KotlinLogging.logger {}

    override fun onModelEvent(event: ModelEvent) {
        logger.debug { "model event: $event" }
    }

    override fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolCallRequested -> logger.info { "tool call requested: ${event.call.name} args=${event.call.arguments}" }
            is AgentEvent.ToolResult -> logger.info { "tool result (isError=${event.isError}): ${event.callId}" }
            is AgentEvent.StepCompleted -> logger.info { "step completed: ${event.step}" }
            is AgentEvent.Completed -> logger.info { "completed, usage=${event.usage}" }
            is AgentEvent.Terminated -> logger.info { "terminated, reason=${event.reason}, usage=${event.usage}" }
            is AgentEvent.Failed -> logger.warn { "failed: ${event.error.message}" }
            is AgentEvent.TextDelta -> {}
            is AgentEvent.ReasoningDelta -> {}
        }
    }

    override fun onStep(state: AgentState) {
        logger.debug { "step ${state.globalStep}: ${state.messages.size} messages" }
    }
}
