package org.koaks.runtime.policy

import org.koaks.framework.loop.AgentResult

/** Circuit-breaker tuning: trip after [failureThreshold] consecutive failures, then cool down. */
data class CircuitBreakerPolicy(
    val failureThreshold: Int = 5,
    val resetTimeoutMillis: Long = 30_000,
)

/**
 * How a supervised run recovers from failure: bounded retries with exponential backoff,
 * an optional per-agent circuit breaker, a predicate deciding what counts as retriable,
 * and an optional [recover] hook that rewrites the input for the next attempt (a
 * lightweight checkpoint/restart).
 */
data class SupervisionPolicy(
    val maxRetries: Int = 2,
    val initialBackoffMillis: Long = 100,
    val backoffFactor: Double = 2.0,
    val maxBackoffMillis: Long = 5_000,
    val circuitBreaker: CircuitBreakerPolicy? = null,
    val retryOn: (AgentResult) -> Boolean = { it is AgentResult.Failed },
    val recover: (suspend (attempt: Int, last: AgentResult) -> String)? = null,
)
