package org.koaks.runtime.context

import kotlinx.coroutines.currentCoroutineContext
import org.koaks.framework.model.Message
import org.koaks.runtime.resource.RuntimeContext

private suspend fun ctx(): RuntimeContext =
    currentCoroutineContext()[RuntimeContext]
        ?: error("context store is only available inside a runtime-spawned agent")

/**
 * Stores [messages] in the shared context store from within a runtime-spawned agent.
 * [ContextScope.PRIVATE] blocks are owned by the calling instance automatically.
 */
suspend fun putContext(messages: List<Message>, scope: ContextScope = ContextScope.GLOBAL): ContextRef {
    val c = ctx()
    val owner = if (scope == ContextScope.PRIVATE) c.runId else null
    return c.context.put(messages, scope, owner)
}

/** Layers [added] over [parent] as a copy-on-write delta. */
suspend fun deltaContext(
    parent: ContextRef,
    added: List<Message>,
    scope: ContextScope = ContextScope.GLOBAL,
): ContextRef {
    val c = ctx()
    val owner = if (scope == ContextScope.PRIVATE) c.runId else null
    return c.context.delta(parent, added, scope, owner)
}

/** Resolves [ref] to its full message list, enforcing this instance's read permissions. */
suspend fun resolveContext(ref: ContextRef): List<Message> {
    val c = ctx()
    return c.context.resolve(ref, c.runId)
}
