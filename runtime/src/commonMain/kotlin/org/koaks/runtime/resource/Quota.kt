package org.koaks.runtime.resource

/**
 * A pure-Kotlin resource quota for one run instance — the cooperative analogue of a
 * cgroup. Every dimension is optional (`null` = unbounded).
 *
 * Enforceability note (cooperative kernel, no preemption): the runtime enforces the
 * dimensions it can observe on the public [org.koaks.framework.loop.AgentEvent] stream —
 * [maxSteps], [maxToolCalls] and [wallClockMillis]. A **token budget** is NOT observable
 * incrementally from that stream, so it is delegated to the agent's own native
 * `runBudget { maxTotalTokens = ... }` (which core enforces against `AgentState.usage`).
 * Concurrency is a runtime-wide setting ([org.koaks.runtime.AgentRuntimeConfig.maxConcurrency]).
 */
class Quota(
    val maxSteps: Int? = null,
    val maxToolCalls: Int? = null,
    val wallClockMillis: Long? = null,
) {
    companion object {
        val UNLIMITED = Quota()
    }
}

/** Builder for the `defaultQuota { }` / `quota { }` DSL. */
class QuotaBuilder {
    var maxSteps: Int? = null
    var maxToolCalls: Int? = null
    var wallClockMillis: Long? = null

    fun build(): Quota = Quota(maxSteps, maxToolCalls, wallClockMillis)
}

/** Builds a [Quota] from a DSL block. */
fun quota(block: QuotaBuilder.() -> Unit): Quota = QuotaBuilder().apply(block).build()
