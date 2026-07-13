package examples

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * By default `spawn` is await-only: the instance runs like a background process and you
 * collect its [org.koaks.framework.loop.AgentResult] via `handle.await()`. To stream the
 * output while it runs, spawn with `observe = true` and drain `handle.events` — the
 * instance's "stdout". This does not change `await()`, which still returns the terminal
 * result.
 *
 * Contract: observed events are retained losslessly and may be collected once, either while
 * the run is active or after `await()`. Runtime-boundary stops (quota / timeout / cancel) always
 * end the stream with a terminal event. If that collection stops early, its buffered tail and
 * subsequent events are discarded without interrupting the run. Consume the stream when
 * observation is enabled so retained events do not occupy memory longer than necessary.
 */
private class SlowEchoModel(private val chunks: List<String>) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        for (chunk in chunks) {
            delay(60) // pretend tokens arrive over time
            emit(ModelEvent.TextDelta(chunk))
        }
        emit(ModelEvent.Completed(Usage(promptTokens = 8, completionTokens = chunks.size, totalTokens = 8 + chunks.size)))
    }
}

private fun poemAgent() = agent {
    name = "poet"
    model { custom(SlowEchoModel(listOf("Roses ", "are ", "red, ", "koaks ", "streams ", "ahead."))) }
    terminateAfter(maxSteps = 3)
}

fun main() = runBlocking {
    val runtime = AgentRuntime { maxConcurrency = 4 }

    runtime.use { rt ->
        // ------------------------------------------------------------------
        // 1) Streaming via Runtime: spawn(observe = true) + collect handle.events.
        //    Collect concurrently with the run so text prints as it is produced.
        // ------------------------------------------------------------------
        println("== 1) spawn(observe = true): stream tokens as they arrive ==")
        val handle = rt.spawn(poemAgent(), "write a two-line poem", observe = true)

        coroutineScope {
            val streamed = async {
                buildString {
                    handle.events.collect { event ->
                        if (event is AgentEvent.TextDelta) {
                            print(event.text) // live output
                            append(event.text)
                        }
                    }
                    println()
                }
            }
            // await() still returns the terminal result, independently of the stream.
            val result = handle.await()
            val collected = streamed.await()
            println("  await().text   = ${result.text}")
            println("  streamed text  = $collected")
            println("  match          = ${result.text == collected}")
        }

        // ------------------------------------------------------------------
        // 2) Default path is unchanged: observe defaults to false, events is empty,
        //    await() is all you need. Zero streaming overhead.
        // ------------------------------------------------------------------
        println("\n== 2) default spawn: await-only, no streaming overhead ==")
        val plain = rt.spawn(poemAgent(), "same task, no streaming")
        println("  result: ${plain.await().text}")
    }
}
