package org.koaks.runtime.observability

/**
 * An aggregate snapshot of the runtime: instance counts by lifecycle state plus summed
 * usage across all known instances. Computed on demand from the run registry.
 */
data class RuntimeMetrics(
    val total: Int,
    val created: Int,
    val threadQueued: Int,
    val ready: Int,
    val running: Int,
    val waiting: Int,
    val suspended: Int,
    val committing: Int,
    val finished: Int,
    val failed: Int,
    val cancelled: Int,
    val totalTokens: Int,
    val totalSteps: Int,
    val totalToolCalls: Int,
)
