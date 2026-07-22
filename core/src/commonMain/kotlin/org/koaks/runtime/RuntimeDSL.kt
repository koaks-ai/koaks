package org.koaks.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.resource.Quota

/**
 * Block-scoped runtime: creates a runtime, runs [block] with it as receiver, and closes
 * it deterministically on exit (analogous to `runBlocking` for coroutines). Recovers the
 * "it just works" feel of a global default without leaking one.
 */
suspend fun <R> withAgentRuntime(
    maxConcurrency: Int = Int.MAX_VALUE,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    defaultQuota: Quota = Quota.UNLIMITED,
    block: suspend AgentRuntime.() -> R,
): R {
    val runtime = AgentRuntime {
        this.maxConcurrency = maxConcurrency
        this.dispatcher = dispatcher
        this.defaultQuota = defaultQuota
    }
    try {
        return runtime.block()
    } finally {
        runtime.close()
    }
}

/**
 * Fluent explicit-runtime entry points. Runtime and Agent live in `koaks-core`; these
 * helpers are alternatives to calling `runtime.run(agent, ...)` directly.
 */
suspend fun Agent.runIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
    thread: ThreadId? = null,
): AgentResult = runtime.run(this, input, priority, quota, contextRefs = contextRefs, thread = thread)

suspend fun Agent.runIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
    thread: String,
): AgentResult = runIn(runtime, input, priority, quota, contextRefs, ThreadId(thread))

fun Agent.streamIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
    thread: ThreadId? = null,
): Flow<AgentEvent> = runtime.stream(this, input, priority, quota, contextRefs = contextRefs, thread = thread)

fun Agent.streamIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
    thread: String,
): Flow<AgentEvent> = streamIn(runtime, input, priority, quota, contextRefs, ThreadId(thread))

fun Agent.spawnIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
    thread: ThreadId? = null,
): AgentHandle = runtime.spawn(this, input, priority, quota, contextRefs = contextRefs, thread = thread)

fun Agent.spawnIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
    thread: String,
): AgentHandle = spawnIn(runtime, input, priority, quota, contextRefs, ThreadId(thread))

/** Awaits every handle's terminal result, preserving order. */
suspend fun awaitAll(vararg handles: AgentHandle): List<AgentResult> = handles.map { it.await() }

/** Awaits every handle's terminal result, preserving order. */
suspend fun Collection<AgentHandle>.awaitAll(): List<AgentResult> = map { it.await() }
