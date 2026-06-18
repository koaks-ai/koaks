package org.koaks.framework.policy

import org.koaks.framework.loop.AgentState

/** Strongly-typed reason for a policy-driven agent stop. */
sealed interface TerminationReason {
    data class MaxSteps(val maxSteps: Int) : TerminationReason
    data class MaxTokens(val maxTokens: Int) : TerminationReason
    data class RunBudgetSteps(val maxTotalSteps: Int) : TerminationReason
    data class RunBudgetTokens(val maxTotalTokens: Int) : TerminationReason
    data class Custom(val message: String) : TerminationReason
}

/** The result of evaluating a termination guard against the current loop state. */
sealed interface TerminationDecision {
    data object Continue : TerminationDecision
    data class Stop(val reason: TerminationReason) : TerminationDecision
}

/**
 * Decides when the agent loop should stop. Replaces the old hard-coded
 * `MAX_TOOL_CALL_EPOCH = 30`.
 */
fun interface TerminationPolicy {
    fun evaluate(state: AgentState): TerminationDecision

    companion object {
        fun maxSteps(n: Int): TerminationPolicy = TerminationPolicy {
            if (it.globalStep >= n) {
                TerminationDecision.Stop(TerminationReason.MaxSteps(n))
            } else {
                TerminationDecision.Continue
            }
        }

        fun maxTokens(n: Int): TerminationPolicy = TerminationPolicy {
            if (it.usage.totalTokens >= n) {
                TerminationDecision.Stop(TerminationReason.MaxTokens(n))
            } else {
                TerminationDecision.Continue
            }
        }

        /** Stops as soon as ANY of the given policies decides to stop, reporting its reason. */
        fun anyOf(vararg policies: TerminationPolicy): TerminationPolicy =
            TerminationPolicy { state ->
                policies.firstNotNullOfOrNull {
                    when (val decision = it.evaluate(state)) {
                        TerminationDecision.Continue -> null
                        is TerminationDecision.Stop -> decision
                    }
                } ?: TerminationDecision.Continue
            }
    }
}
