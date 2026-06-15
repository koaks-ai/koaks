package org.koaks.framework.middleware

import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.ModelEvent

/**
 * Push-style observer. To watch events (tracing, token counting, logging) you
 * implement this — you NEVER collect a flow, so cold-flow double-subscription is
 * impossible by construction. The loop pushes from the single tee point.
 */
interface AgentListener {
    fun onModelEvent(event: ModelEvent) {}
    fun onAgentEvent(event: AgentEvent) {}
    fun onStep(state: AgentState) {}
}
