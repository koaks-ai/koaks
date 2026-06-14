package org.koaks.framework.policy

import org.koaks.framework.loop.AgentState

/**
 * Decides when the agent loop should stop. Replaces the old hard-coded
 * `MAX_TOOL_CALL_EPOCH = 30`.
 */
fun interface TerminationPolicy {
    fun shouldStop(state: AgentState): Boolean

    companion object {
        fun maxSteps(n: Int): TerminationPolicy = TerminationPolicy { it.globalStep >= n }

        fun maxTokens(n: Int): TerminationPolicy = TerminationPolicy { it.usage.totalTokens >= n }

        fun and(vararg policies: TerminationPolicy): TerminationPolicy =
            TerminationPolicy { state -> policies.any { it.shouldStop(state) } }
    }
}
