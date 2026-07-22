package examples

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.awaitAll
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.resource.quota
import org.koaks.runtime.sched.taskGraph

/**
 * A self-contained tour of the Runtime built into `koaks-core` that runs WITHOUT any API keys: it uses a
 * trivial [EchoModel] so you can see the kernel's behavior (concurrent spawn, priority,
 * a task DAG, the context store, metrics) deterministically.
 *
 * For the **recommended real-model usage** (spawn / context / awaitAll / TaskGraph /
 * stream / metrics), see [AgentRuntimeExample].
 *
 * Run this `main` to see the runtime coordinate several agents like an OS coordinates
 * processes.
 */
private class EchoModel(private val reply: String) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        emit(ModelEvent.TextDelta(reply))
        emit(ModelEvent.Completed(Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15)))
    }
}

private fun echoAgent(name: String, reply: String) = agent {
    id = name
    this.name = name
    model { custom(EchoModel(reply)) }
    terminateAfter(maxSteps = 3)
}

fun main() = runBlocking {
    val runtime = AgentRuntime {
        maxConcurrency = 4
        defaultQuota = quota { maxSteps = 10 }
    }

    runtime.use { rt ->
        println("== 1) Concurrent spawn (like launching several processes) ==")
        val handles = (1..5).map { i -> rt.spawn(echoAgent("worker-$i", "done-$i"), "task $i") }
        handles.awaitAll().forEach { println("  -> ${it.text}") }

        println("\n== 2) Task DAG: research -> {frontend, backend} -> review ==")
        val graph = taskGraph {
            task("research", echoAgent("researcher", "spec: build a todo app"), input = "analyze requirements")
            task("frontend", echoAgent("frontend", "ui done"), dependsOn = listOf("research")) { deps ->
                "implement UI for: ${deps.getValue("research").text}"
            }
            task("backend", echoAgent("backend", "api done"), dependsOn = listOf("research")) { deps ->
                "implement API for: ${deps.getValue("research").text}"
            }
            task("review", echoAgent("reviewer", "LGTM"), dependsOn = listOf("frontend", "backend")) { deps ->
                "review: ${deps.getValue("frontend").text} + ${deps.getValue("backend").text}"
            }
        }
        rt.submit(graph).forEach { (id, result) -> println("  $id -> ${result.text}") }

        println("\n== 3) Shared context by reference (no copying) ==")
        val shared = rt.context.put(
            (1..100).map { Message.user("shared knowledge line $it") },
            scope = ContextScope.GLOBAL,
        )
        val forAgentA = rt.context.delta(shared, listOf(Message.user("private note for A")))
        println("  shared block ref: ${shared.id}")
        println("  A's view size: ${rt.context.resolve(forAgentA, requester = null).size} messages (100 shared + 1 delta)")

        println("\n== 4) Runtime metrics ==")
        val m = rt.metrics()
        println("  total=${m.total} finished=${m.finished} failed=${m.failed} tokens=${m.totalTokens}")
    }
}
