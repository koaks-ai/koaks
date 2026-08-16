package examples

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.agent
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.framework.loop.done
import org.koaks.memory.summarizing.InMemoryAppendMemoryProvider
import org.koaks.memory.summarizing.InMemorySummaryStateStore
import org.koaks.memory.summarizing.SummarizingMemoryProvider
import org.koaks.runtime.AgentRuntime

/** Demonstrates lossless compaction without a Provider API key. */
fun main() = runBlocking {
    val thread = ThreadId("durable-summary-demo")
    val raw = InMemoryAppendMemoryProvider(MemoryProviderId("raw-history"))
    val summaries = InMemorySummaryStateStore()
    val memory = SummarizingMemoryProvider(
        id = MemoryProviderId("summarized-history"),
        delegate = raw,
        stateStore = summaries,
        model = FixedTextModel("Earlier turns agreed on a bounded event journal."),
        maxTokens = 100,
        keepRecentTurns = 1,
    )
    val assistant = agent {
        id = "summary-demo-agent"
        model { custom(FixedTextModel("Acknowledged.", promptTokens = 200)) }
        memory { custom(memory.id, memory) }
    }

    assistant.use { agent ->
        AgentRuntime().use { runtime ->
            runtime.run(agent, "Choose the run primitive.", thread = thread)
            runtime.run(agent, "Define event retention.", thread = thread)
            runtime.run(agent, "Define recovery semantics.", thread = thread)
        }
    }

    val projected = memory.open(thread).load(emptyList()).transcript
    val original = raw.open(thread).load(emptyList()).transcript
    println("Model-facing projection:")
    projected.printMessages()
    println("\nRaw append-only history:")
    original.printMessages()
}

private class FixedTextModel(
    private val text: String,
    private val promptTokens: Int = 10,
) : LanguageModel {
    override val capabilities = ModelCapabilities()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        emit(ModelEvent.TextDelta(text))
        emit(
            done(
                Usage(
                    promptTokens = promptTokens,
                    completionTokens = 1,
                    totalTokens = promptTokens + 1,
                ),
            ),
        )
    }
}

private fun List<ModelItem>.printMessages() {
    filterIsInstance<ModelItem.Message>().forEach { message ->
        val label = when (message.role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool"
        }
        println("  $label: ${message.text}")
    }
}
