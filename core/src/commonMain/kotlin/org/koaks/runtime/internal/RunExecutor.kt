package org.koaks.runtime.internal

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.WindowMemory
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.conversation.ConversationRegistry
import org.koaks.runtime.observability.RuntimeEvent
import org.koaks.runtime.policy.Quota
import org.koaks.runtime.run.RunState

/** Executes one run against its conversation memory, limits and event journal. */
internal class RunExecutor(
    private val scheduler: ConcurrencyScheduler,
    private val threads: ConversationRegistry,
    private val tree: RunTreeCoordinator,
    private val emit: (RuntimeEvent) -> Unit,
) {
    suspend fun execute(
        gate: InstanceActivityGate,
        agent: Agent,
        input: String,
        contextPrefix: List<ModelItem>,
        record: RunRecord,
        control: RunControl,
        priority: Int,
        quota: Quota,
        sink: EventSink,
        thread: ThreadId?,
        runOwner: Boolean,
        runReservation: ConversationRegistry.RunReservation?,
        structuredSpec: OutputSpec?,
        resume: Boolean,
        eventDetail: EventDetail,
    ): AgentResult {
        var threadLease: ConversationRegistry.ThreadLease? = null
        try {
            val memory = if (thread != null) {
                if (runOwner) {
                    val lease = requireNotNull(runReservation).await().also { threadLease = it }
                    lease.memory().let { if (it === org.koaks.framework.memory.NoMemory) WindowMemory(Int.MAX_VALUE) else it }
                } else {
                    val owner = tree.threadOwnerRunId(requireNotNull(record.snapshot.parent), thread)
                    threads.joinActiveRun(thread, owner, agent)
                    threads.memory(thread)
                }
            } else {
                WindowMemory(Int.MAX_VALUE)
            }

            record.markReady()
            val result = scheduler.withSlot(priority) { lease ->
                gate.attachLease(lease)
                record.markRunning()
                emit(RuntimeEvent.Running(record.runId, agent.id, thread, agent.name))
                try { runWithQuota(
                    agent,
                    input.takeUnless { resume },
                    contextPrefix,
                    memory,
                    record,
                    control,
                    quota,
                    sink,
                    structuredSpec,
                    eventDetail,
                ) } finally {
                    kotlinx.coroutines.currentCoroutineContext()[org.koaks.framework.loop.RunStepServices]?.close?.invoke()
                }
            }
            record.sealChildren()
            if (!record.snapshot.state.isTerminal && tree.hasActiveChildren(record.runId)) {
                record.markWaiting()
                emit(RuntimeEvent.Waiting(record.runId, agent.id, thread))
            }
            val childFailure = tree.awaitChildren(
                record.runId,
                reportCapturedFailures = true,
                reportCapturedCancellations = result is AgentResult.Completed,
            )
            if (result !is AgentResult.Failed && childFailure != null) {
                val error = AgentError.ModelError(
                    message = "child run '${childFailure.runId.value}' failed: ${childFailure.error.message}",
                    retriable = false,
                    cause = childFailure.error.cause,
                )
                record.markFailed(error, result.usage)
                emit(RuntimeEvent.Failed(record.runId, agent.id, thread, error))
                sink.emit(AgentEvent.Failed(error, result.usage))
                return AgentResult.Failed(error, result.usage)
            }
            when (result) {
                is AgentResult.Completed -> if (record.beginCommitting()) {
                    withContext(NonCancellable) {
                        record.markFinished(result.usage)
                        emit(RuntimeEvent.Finished(record.runId, agent.id, thread, result.usage))
                    }
                }
                is AgentResult.Incomplete -> if (record.beginCommitting()) {
                    withContext(NonCancellable) {
                        record.markFinished(result.usage)
                        emit(
                            RuntimeEvent.Incomplete(
                                record.runId,
                                agent.id,
                                thread,
                                result.reason,
                                result.usage,
                            ),
                        )
                    }
                }
                is AgentResult.Terminated -> if (record.beginCommitting()) {
                    withContext(NonCancellable) {
                        record.markFinished(result.usage)
                        emit(RuntimeEvent.Terminated(record.runId, agent.id, thread, result.reason))
                    }
                }
                is AgentResult.Failed -> Unit
            }
            return result
        } catch (cancelled: CancellationException) {
            record.sealChildren()
            record.markCancelled()
            if (record.snapshot.state == RunState.CANCELLED && record.claimCancellationEvent()) {
                emit(RuntimeEvent.Cancelled(record.runId, agent.id, thread))
            }
            tree.cancelDescendants(record.runId, "parent ${record.runId} cancelled")
            withContext(NonCancellable) {
                tree.awaitChildren(
                    record.runId,
                    reportCapturedFailures = false,
                    reportCapturedCancellations = false,
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            record.sealChildren()
            tree.cancelDescendants(record.runId, "parent ${record.runId} failed")
            withContext(NonCancellable) {
                tree.awaitChildren(
                    record.runId,
                    reportCapturedFailures = false,
                    reportCapturedCancellations = false,
                )
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                threadLease?.release()
            }
        }
    }

    private suspend fun runWithQuota(
        agent: Agent,
        input: String?,
        contextPrefix: List<ModelItem>,
        memory: ThreadMemory,
        record: RunRecord,
        control: RunControl,
        quota: Quota,
        sink: EventSink,
        structuredSpec: OutputSpec?,
        eventDetail: EventDetail,
    ): AgentResult {
        val wall = quota.wallClockMillis
            ?: return collectStream(
                agent,
                input,
                contextPrefix,
                memory,
                record,
                control,
                quota,
                sink,
                structuredSpec,
                eventDetail,
            )
        return try {
            withTimeout(wall.milliseconds) {
                collectStream(
                    agent,
                    input,
                    contextPrefix,
                    memory,
                    record,
                    control,
                    quota,
                    sink,
                    structuredSpec,
                    eventDetail,
                    )
            }
        } catch (_: TimeoutCancellationException) {
            val error = AgentError.Timeout("agent wall-clock", wall)
            val usage = record.snapshot.usage
            record.markFailed(error, usage)
            emit(RuntimeEvent.Failed(record.runId, record.snapshot.agentId, record.snapshot.threadId, error))
            tree.cancelDescendants(record.runId, "parent ${record.runId} timed out")
            sink.emit(AgentEvent.Failed(error, usage))
            AgentResult.Failed(error, usage)
        }
    }

    private suspend fun collectStream(
        agent: Agent,
        input: String?,
        contextPrefix: List<ModelItem>,
        memory: ThreadMemory,
        record: RunRecord,
        control: RunControl,
        quota: Quota,
        sink: EventSink,
        structuredSpec: OutputSpec?,
        eventDetail: EventDetail,
    ): AgentResult {
        var terminal: AgentEvent.Terminal? = null
        var lastFailure: AgentError? = null
        var usage = Usage.ZERO
        var steps = 0
        var toolCalls = 0
        val stepText = StringBuilder()
        var lastAssistant = ""

        try {
            val events = if (structuredSpec == null) {
                agent.executeStream(input, memory, eventDetail, contextPrefix)
            } else {
                agent.executeStructuredStream(input, memory, structuredSpec, eventDetail, contextPrefix)
            }
            events.collect { event ->
                if (control.isPaused) {
                    record.markPaused()
                    emit(RuntimeEvent.Suspended(record.runId, agent.id, record.snapshot.threadId))
                    val exec = kotlinx.coroutines.currentCoroutineContext()[org.koaks.framework.loop.AgentExecutionContext]
                    if (exec != null) exec.waiting { control.awaitResumed() } else control.awaitResumed()
                    record.markRunning()
                    emit(RuntimeEvent.Resumed(record.runId, agent.id, record.snapshot.threadId))
                }

                sink.emit(event)
                record.observe(event)
                when (event) {
                    is AgentEvent.TextDelta -> stepText.append(event.text)
                    is AgentEvent.ToolCallRequested -> {
                        toolCalls++
                        if (quota.maxToolCalls != null && toolCalls > quota.maxToolCalls) {
                            throw QuotaSignal("maxToolCalls=${quota.maxToolCalls}")
                        }
                    }
                    is AgentEvent.StepCompleted -> {
                        steps++
                        lastAssistant = stepText.toString()
                        stepText.clear()
                        if (quota.maxSteps != null && steps >= quota.maxSteps) {
                            throw QuotaSignal("maxSteps=${quota.maxSteps}")
                        }
                    }
                    is AgentEvent.Terminal -> {
                        terminal = event
                        usage = event.usage
                    }
                    is AgentEvent.Failed -> {
                        lastFailure = event.error
                        if (event.usage != Usage.ZERO) usage = event.usage
                    }
                    else -> Unit
                }
            }
        } catch (quotaSignal: QuotaSignal) {
            val currentUsage = record.snapshot.usage
            record.markCancelled()
            if (record.snapshot.state == RunState.CANCELLED && record.claimCancellationEvent()) {
                emit(RuntimeEvent.Cancelled(record.runId, agent.id, record.snapshot.threadId))
            }
            tree.cancelDescendants(record.runId, "parent ${record.runId} quota exceeded")
            val reason = TerminationReason.Custom("quota exceeded: ${quotaSignal.dimension}")
            val message = ModelItem.assistant(stepText.toString().ifEmpty { lastAssistant })
            sink.emit(AgentEvent.Terminated(message, currentUsage, reason))
            return AgentResult.Terminated(message, currentUsage, reason)
        } catch (failure: AgentFrameworkException) {
            val currentUsage = record.snapshot.usage
            val error = failure.error
            val event = AgentEvent.Failed(error, currentUsage)
            record.markFailed(error, currentUsage)
            emit(RuntimeEvent.Failed(record.runId, agent.id, record.snapshot.threadId, error))
            tree.cancelDescendants(record.runId, "parent ${record.runId} failed")
            agent.listeners.forEach { it.onAgentEvent(event) }
            sink.emit(event)
            return AgentResult.Failed(error, currentUsage)
        }

        return when (val term = terminal) {
            is AgentEvent.Completed -> AgentResult.Completed(term.message, usage)
            is AgentEvent.Incomplete -> AgentResult.Incomplete(term.message, usage, term.reason)
            is AgentEvent.Terminated -> AgentResult.Terminated(term.message, usage, term.reason)
            null -> {
                val error = lastFailure ?: AgentError.ModelError("agent produced no terminal event", retriable = false)
                record.markFailed(error, usage)
                emit(RuntimeEvent.Failed(record.runId, agent.id, record.snapshot.threadId, error))
                tree.cancelDescendants(record.runId, "parent ${record.runId} failed")
                AgentResult.Failed(error, usage)
            }
        }
    }

}

private class QuotaSignal(val dimension: String) : CancellationException("quota exceeded: $dimension")
