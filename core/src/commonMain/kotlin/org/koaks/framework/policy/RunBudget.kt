package org.koaks.framework.policy

import org.koaks.framework.loop.AgentState

/**
 * The whole-run global guard. Independent of any single agent's
 * [TerminationPolicy], it accumulates across the entire run — and (in L5) across
 * handoffs — using [AgentState.globalStep] and [AgentState.usage], which are NEVER
 * reset. It is the final brake against runaway loops (e.g. agents handing off back
 * and forth forever).
 *
 * `null` fields disable that dimension. Even without handoffs (this stage), it is a
 * useful belt-and-suspenders cap distinct from per-agent `maxSteps`.
 */
data class RunBudget(
    val maxTotalSteps: Int? = null,
    val maxTotalTokens: Int? = null,
) {
    fun evaluate(state: AgentState): TerminationDecision {
        if (maxTotalSteps != null && state.globalStep >= maxTotalSteps) {
            return TerminationDecision.Stop(TerminationReason.RunBudgetSteps(maxTotalSteps))
        }
        if (maxTotalTokens != null && state.usage.totalTokens >= maxTotalTokens) {
            return TerminationDecision.Stop(TerminationReason.RunBudgetTokens(maxTotalTokens))
        }
        return TerminationDecision.Continue
    }

    companion object {
        /** No global cap — per-agent termination is the only limit. */
        val UNLIMITED = RunBudget()
    }
}
