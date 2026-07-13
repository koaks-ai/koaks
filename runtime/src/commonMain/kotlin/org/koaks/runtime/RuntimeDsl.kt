package org.koaks.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult
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
 * Fluent alternative to [AgentRuntime.spawn]: `agent.spawnIn(runtime, "task")`. Defined
 * in the runtime module so the dependency direction stays `runtime -> core` (core's
 * [Agent] never learns about the runtime).
 */
fun Agent.spawnIn(
    runtime: AgentRuntime,
    input: String,
    priority: Int = 0,
    contextRefs: List<ContextRef> = emptyList(),
    observe: Boolean = false,
): AgentHandle = runtime.spawn(this, input, priority, contextRefs = contextRefs, observe = observe)

/** Awaits every handle's terminal result, preserving order. */
suspend fun awaitAll(vararg handles: AgentHandle): List<AgentResult> = handles.map { it.await() }

/** Awaits every handle's terminal result, preserving order. */
suspend fun Collection<AgentHandle>.awaitAll(): List<AgentResult> = map { it.await() }
