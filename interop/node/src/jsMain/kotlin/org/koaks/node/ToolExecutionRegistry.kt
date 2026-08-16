package org.koaks.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentId
import org.koaks.framework.tool.ToolInvocationContext
import org.koaks.runtime.ipc.RuntimeMessage
import org.koaks.runtime.resource.RuntimeContext

internal class NodeBridgeException(
    val code: String,
    message: String,
) : IllegalStateException(message)

/**
 * Keeps Kotlin runtime objects behind short-lived opaque ids while a JS Tool callback is
 * active. Reverse Bridge calls run on their own scheduler branch; saved Agent handles are
 * deliberately owned by KoaksBridge instead and therefore outlive this capability.
 */
internal class ToolExecutionRegistry {
    internal class Execution internal constructor(
        val id: String,
        val runtimeContext: RuntimeContext,
        val executionContext: AgentExecutionContext,
        val invocationContext: ToolInvocationContext?,
    ) {
        private val jobs = mutableSetOf<Job>()
        private val replyTokens = mutableMapOf<String, RuntimeMessage>()
        private var replySequence = 0L
        var active: Boolean = true
            private set

        fun track(job: Job) {
            checkActive()
            jobs += job
            job.invokeOnCompletion { jobs -= job }
        }

        fun untrack(job: Job) {
            jobs -= job
        }

        fun issueReplyToken(message: RuntimeMessage): String {
            checkActive()
            val token = "reply-${id}-${++replySequence}"
            replyTokens[token] = message
            return token
        }

        fun takeReplyToken(token: String): RuntimeMessage =
            replyTokens.remove(token)
                ?: throw NodeBridgeException("ipc_reply_invalid", "IPC reply token is invalid or was already used")

        fun close(reason: String) {
            if (!active) return
            active = false
            replyTokens.clear()
            val cancellation = CancellationException(reason)
            jobs.toList().forEach { it.cancel(cancellation) }
            jobs.clear()
        }

        fun checkActive() {
            if (!active) {
                throw NodeBridgeException("tool_context_expired", "Tool RuntimeContext '$id' has expired")
            }
        }
    }

    private val executions = mutableMapOf<String, Execution>()
    private var sequence = 0L

    fun open(
        runtimeContext: RuntimeContext,
        executionContext: AgentExecutionContext,
        invocationContext: ToolInvocationContext? = null,
    ): Execution {
        val id = "tool-execution-${++sequence}"
        return Execution(id, runtimeContext, executionContext, invocationContext).also { executions[id] = it }
    }

    fun close(id: String, reason: String = "Tool execution completed") {
        executions.remove(id)?.close(reason)
    }

    fun closeAgent(agentId: AgentId, reason: String = "Agent closed") {
        executions.values
            .filter { it.runtimeContext.agentId == agentId }
            .map { it.id }
            .forEach { close(it, reason) }
    }

    fun closeAll(reason: String = "Runtime closed") {
        executions.keys.toList().forEach { close(it, reason) }
    }

    fun track(id: String, job: Job) {
        execution(id).track(job)
    }

    suspend fun <T> execute(id: String, block: suspend (Execution) -> T): T {
        val record = execution(id)
        val job = currentCoroutineContext()[Job]
            ?: throw NodeBridgeException("lifecycle_error", "Tool context operation has no coroutine Job")
        record.track(job)
        val branch = record.executionContext.forkBranch()
        record.checkActive()
        return try {
            withContext(record.runtimeContext + record.executionContext) {
                branch.run {
                    record.checkActive()
                    block(record)
                }
            }
        } finally {
            record.untrack(job)
        }
    }

    private fun execution(id: String): Execution =
        executions[id]
            ?: throw NodeBridgeException("tool_context_expired", "Tool RuntimeContext '$id' has expired")
}
