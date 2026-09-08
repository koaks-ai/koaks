package org.koaks.runtime.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koaks.framework.loop.AgentResult
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.workflow.TaskGraph

/** Executes a validated graph using the ordinary run API. */
internal class TaskGraphExecutor(private val runtime: AgentRuntime) {
    suspend fun submit(graph: TaskGraph): Map<String, AgentResult> = coroutineScope {
        val results = graph.nodes.associate { it.id to CompletableDeferred<AgentResult>() }
        graph.nodes.forEach { node ->
            launch {
                try {
                    val deps = node.dependsOn.associateWith { results.getValue(it).await() }
                    val result = runtime.run(node.agent, node.input(deps), node.priority)
                    results.getValue(node.id).complete(result)
                } catch (failure: Throwable) {
                    results.getValue(node.id).completeExceptionally(failure)
                    throw failure
                }
            }
        }
        results.mapValues { it.value.await() }
    }

}
