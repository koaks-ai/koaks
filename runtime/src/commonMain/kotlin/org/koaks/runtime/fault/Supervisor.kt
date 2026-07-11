package org.koaks.runtime.fault

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.model.AgentError
import org.koaks.runtime.observe.RuntimeEvent
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.TimeSource

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

/**
 * Drives retry/backoff/circuit-breaking around repeated attempts of an agent run. It is
 * agent-name keyed for the circuit breaker so a persistently failing agent trips fast
 * instead of hammering a downstream model. Pure, cooperative, and time-source based.
 */
internal class Supervisor {
    private val mutex = Mutex()
    private val consecutiveFailures = HashMap<String, Int>()
    private val openMark = HashMap<String, TimeSource.Monotonic.ValueTimeMark>()

    /** Whether a new attempt for [agentName] is currently allowed by the circuit breaker. */
    private suspend fun allow(agentName: String, policy: SupervisionPolicy): Boolean = mutex.withLock {
        val cb = policy.circuitBreaker ?: return@withLock true
        val mark = openMark[agentName] ?: return@withLock true
        if (mark.elapsedNow().inWholeMilliseconds >= cb.resetTimeoutMillis) {
            // Half-open: allow a probe attempt.
            openMark.remove(agentName)
            consecutiveFailures[agentName] = 0
            true
        } else {
            false
        }
    }

    private suspend fun record(agentName: String, success: Boolean, policy: SupervisionPolicy) = mutex.withLock {
        val cb = policy.circuitBreaker
        if (success) {
            consecutiveFailures[agentName] = 0
            openMark.remove(agentName)
        } else if (cb != null) {
            val failures = (consecutiveFailures[agentName] ?: 0) + 1
            consecutiveFailures[agentName] = failures
            if (failures >= cb.failureThreshold) openMark[agentName] = TimeSource.Monotonic.markNow()
        }
    }

    private fun backoffFor(attempt: Int, policy: SupervisionPolicy): Long {
        val raw = policy.initialBackoffMillis * policy.backoffFactor.pow(attempt - 1)
        return min(raw.toLong(), policy.maxBackoffMillis)
    }

    /**
     * Runs [attempt] (which spawns and awaits one instance) under [policy], retrying on
     * failure with backoff until success or retries are exhausted / the circuit is open.
     */
    suspend fun run(
        agentName: String,
        initialInput: String,
        policy: SupervisionPolicy,
        emit: (RuntimeEvent) -> Unit,
        attempt: suspend (input: String) -> AgentResult,
    ): AgentResult {
        if (!allow(agentName, policy)) {
            emit(RuntimeEvent.CircuitOpen(agentName))
            return AgentResult.Failed(AgentError.ModelError("circuit open for agent '$agentName'", retriable = false))
        }

        var tries = 0
        var input = initialInput
        while (true) {
            val result = attempt(input)
            if (!policy.retryOn(result)) {
                record(agentName, success = true, policy)
                return result
            }

            record(agentName, success = false, policy)
            if (tries >= policy.maxRetries) return result
            if (!allow(agentName, policy)) {
                emit(RuntimeEvent.CircuitOpen(agentName))
                return result
            }

            tries++
            val backoff = backoffFor(tries, policy)
            emit(RuntimeEvent.Retrying(agentName, tries, backoff))
            delay(backoff)
            input = policy.recover?.invoke(tries, result) ?: input
        }
    }
}
