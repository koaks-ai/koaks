package org.koaks.framework.policy

import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentState

/** Shared checks for normal calls, retries, and structured finalization. */
internal class StepPolicyEvaluator(private val agent: Agent) {
    suspend fun beforeModel(state: AgentState): TerminationDecision =
        when (val budget = agent.runBudget.evaluate(state)) {
            TerminationDecision.Continue -> agent.suspendTermination?.evaluate(state) ?: agent.termination.evaluate(state)
            is TerminationDecision.Stop -> budget
        }

    suspend fun afterModel(state: AgentState): TerminationDecision {
        val budget = agent.runBudget
        if (budget.maxTotalTokens != null && state.usage.totalTokens >= budget.maxTotalTokens) {
            return TerminationDecision.Stop(TerminationReason.RunBudgetTokens(budget.maxTotalTokens))
        }
        // Step limits admit whole steps; token and custom policies can stop before tools.
        return when (val decision = agent.suspendTermination?.evaluate(state) ?: agent.termination.evaluate(state)) {
            is TerminationDecision.Stop -> if (decision.reason is TerminationReason.MaxSteps) TerminationDecision.Continue else decision
            TerminationDecision.Continue -> TerminationDecision.Continue
        }
    }
}
