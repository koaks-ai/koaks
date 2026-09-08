package org.koaks.runtime.internal

import kotlin.math.min
import kotlin.math.pow
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.observability.RuntimeEvent
import org.koaks.runtime.policy.Quota
import org.koaks.runtime.policy.SupervisionPolicy
import org.koaks.runtime.run.RunHandle
import org.koaks.runtime.run.SupervisedHandle

/**
 * Drives retry/backoff/circuit-breaking around repeated attempts of an agent run. It is
 * agent-name keyed for the circuit breaker so a persistently failing agent trips fast
 * instead of hammering a downstream model. Pure, cooperative, and time-source based.
 */
internal class RetrySupervisor {
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
        onRetrying: (attempt: Int, delayMillis: Long) -> Unit,
        onCircuitOpen: () -> Unit,
        attempt: suspend (input: String) -> AgentResult,
    ): AgentResult {
        if (!allow(agentName, policy)) {
            onCircuitOpen()
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
                onCircuitOpen()
                return result
            }

            tries++
            val backoff = backoffFor(tries, policy)
            onRetrying(tries, backoff)
            delay(backoff)
            input = policy.recover?.invoke(tries, result) ?: input
        }
    }

    fun spawnSupervised(
        manager: RunManager,
        agent: Agent,
        input: String,
        policy: SupervisionPolicy = SupervisionPolicy(),
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): SupervisedHandle {
        val latest = MutableStateFlow<RunHandle?>(null)
        val deferred: Deferred<AgentResult> = manager.scope.async {
            run(
                agentName = agent.name,
                initialInput = input,
                policy = policy,
                onRetrying = { attempt, delayMillis ->
                    val current = latest.value
                    manager.emit(
                        RuntimeEvent.Retrying(
                            runId = current?.runId,
                            agentId = agent.id,
                            agentName = agent.name,
                            threadId = current?.threadId ?: thread,
                            attempt = attempt,
                            delayMillis = delayMillis,
                        ),
                    )
                },
                onCircuitOpen = {
                    val current = latest.value
                    manager.emit(
                        RuntimeEvent.CircuitOpen(
                            runId = current?.runId,
                            agentId = agent.id,
                            agentName = agent.name,
                            threadId = current?.threadId ?: thread,
                        ),
                    )
                },
            ) { attemptInput ->
                val handle = manager.spawn(agent, attemptInput, priority, quota, null, context, thread, null, eventDetail = eventDetail)
                latest.value = handle
                handle.await()
            }
        }
        return SupervisedHandle(deferred) { reason -> latest.value?.cancel(reason) }
    }

}
