package org.koaks.runtime.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.runtime.observability.RuntimeEvent
import org.koaks.runtime.policy.ChildFailurePolicy
import org.koaks.runtime.run.RunHandle
import org.koaks.runtime.run.RunId
import org.koaks.runtime.run.RunSnapshot

private val logger = KotlinLogging.logger {}

/** Parent/child completion, cancellation and captured-failure reporting. */
internal class RunTreeCoordinator(private val registry: RunRegistry, private val emit: (RuntimeEvent) -> Unit) {
    private val records get() = registry.records
    private val handles get() = registry.handles

    fun children(id: RunId): List<RunSnapshot> =
        records.value[id]?.snapshot?.children.orEmpty().mapNotNull { records.value[it]?.snapshot }

    fun cancelDescendants(id: RunId, reason: String) {
        val record = records.value[id] ?: return
        record.snapshot.children.forEach { child ->
            handles.value[child]?.cancel(reason)
            cancelDescendants(child, reason)
        }
    }

    suspend fun awaitChildren(
        id: RunId,
        reportCapturedFailures: Boolean = true,
        reportCapturedCancellations: Boolean = true,
    ): ChildFailure? {
        val parentRecord = records.value[id] ?: return null
        val children = parentRecord.snapshot.children
        var firstFailure: ChildFailure? = null
        children.forEach { child ->
            val handle = handles.value[child] ?: return@forEach
            val failurePolicy = parentRecord.childFailurePolicy(child)

            fun recordFailure(error: AgentError, cancellation: Boolean = false) {
                when (failurePolicy) {
                    ChildFailurePolicy.PROPAGATE -> if (firstFailure == null) {
                        firstFailure = ChildFailure(child, error)
                    }
                    ChildFailurePolicy.CAPTURE -> if (
                        reportCapturedFailures && (!cancellation || reportCapturedCancellations)
                    ) {
                        reportUnhandledChildFailure(id, handle, error)
                    }
                }
            }

            try {
                val result = handle.awaitForParent()
                if (result is AgentResult.Failed) recordFailure(result.error)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                recordFailure(
                    AgentError.ModelError(
                        message = cancelled.message ?: "child run was cancelled",
                        retriable = false,
                        cause = cancelled,
                    ),
                    cancellation = true,
                )
            } catch (failure: Throwable) {
                recordFailure(
                    handle.snapshot.error ?: AgentError.ModelError(
                        message = failure.message ?: "child run failed unexpectedly",
                        retriable = false,
                        cause = failure,
                    ),
                )
            }
        }
        return firstFailure
    }

    private fun reportUnhandledChildFailure(parent: RunId, handle: RunHandle, error: AgentError) {
        if (handle.isFailureObserved || !handle.claimUnhandledFailure()) return
        logger.warn {
            "captured child run '${handle.runId.value}' failed without its result being consumed: ${error.message}"
        }
        emit(
            RuntimeEvent.UnhandledChildFailure(
                parentRunId = parent,
                childRunId = handle.runId,
                childAgentId = handle.agentId,
                error = error,
            ),
        )
    }

    fun hasActiveChildren(id: RunId): Boolean =
        records.value[id]?.snapshot?.children.orEmpty().any { child -> handles.value[child]?.isActive == true }

    fun threadOwnerRunId(from: RunId, thread: ThreadId): RunId {
        var current = from
        while (true) {
            val parent = records.value[current]?.snapshot?.parent ?: return current
            if (records.value[parent]?.snapshot?.threadId != thread) return current
            current = parent
        }
    }

}

internal data class ChildFailure(
    val runId: RunId,
    val error: AgentError,
)
