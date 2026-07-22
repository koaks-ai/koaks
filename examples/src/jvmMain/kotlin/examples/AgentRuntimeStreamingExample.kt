package examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import org.koaks.runtime.AgentRuntime

/**
 * Streaming the output of an AgentRuntime instance — WITHOUT any API keys.
 *
 * `run` is the foreground result path, `stream` is the foreground stdout path, and `spawn`
 * is the background process path returning an AgentHandle. The streaming flow is cold: each
 * collection creates one runtime-managed instance, and stopping collection cancels it.
 *
 * Events are delivered with downstream backpressure and are not retained for late collection.
 */
private class SlowEchoModel(private val chunks: List<String>) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        for (chunk in chunks) {
            delay(1000) // pretend tokens arrive over time
            emit(ModelEvent.TextDelta(chunk))
        }
        emit(ModelEvent.Completed(Usage(promptTokens = 8, completionTokens = chunks.size, totalTokens = 8 + chunks.size)))
    }
}

private fun poemAgent() = agent {
    id = "poem-writer"
    name = "poet"
    model { custom(SlowEchoModel(listOf("Roses ", "are ", "red, ", "koaks ", "streams ", "ahead."))) }
    terminateAfter(maxSteps = 3)
}

fun main() = runBlocking {
    val runtime = AgentRuntime { maxConcurrency = 4 }

    runtime.use { rt ->
        // ------------------------------------------------------------------
        // 1) Foreground streaming: collecting starts one runtime-managed instance.
        // ------------------------------------------------------------------
        println("== 1) runtime.stream: stream tokens as they arrive ==")
        val streamed = StringBuilder()
        rt.stream(poemAgent(), "write a two-line poem").collect { event ->
            if (event is AgentEvent.TextDelta) {
                print(event.text) // live output
                streamed.append(event.text)
            }
            if (event is AgentEvent.Completed) {
                println()
                println("  terminal text  = ${event.message.text}")
                println("  streamed text  = $streamed")
                println("  match          = ${event.message.text == streamed.toString()}")
            }
        }

        // ------------------------------------------------------------------
        // 2) Foreground result path: run waits for the terminal AgentResult.
        // ------------------------------------------------------------------
        println("\n== 2) runtime.run: await the final result ==")
        val result = rt.run(poemAgent(), "same task, final result only")
        println("  result: ${result.text}")

        // ------------------------------------------------------------------
        // 3) Background path: spawn returns a controllable handle.
        // ------------------------------------------------------------------
        println("\n== 3) runtime.spawn: background handle ==")
        val handle = rt.spawn(poemAgent(), "same task, background")
        println("  result: ${handle.await().text}")
    }
}
