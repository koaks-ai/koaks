package org.koaks.java

import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.Agent as CoreAgent
import org.koaks.framework.loop.AgentBuilder as CoreAgentBuilder
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.memory.NoMemoryProvider
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.utils.json.JsonExtractor
import org.koaks.java.internal.virtualFuture
import org.koaks.java.json.JacksonType
import org.koaks.java.tool.ToolSpec
import org.koaks.java.tool.scanAnnotatedTools
import tools.jackson.core.type.TypeReference

/** Java 21 facade over the Kotlin-first immutable Agent definition. */
class Agent private constructor(
    private val delegate: CoreAgent,
) : AutoCloseable {
    val id: String get() = delegate.id.value
    val name: String get() = delegate.name

    @Throws(InterruptedException::class)
    fun run(input: String): AgentResult = runBlocking { delegate.run(input) }

    @Throws(InterruptedException::class)
    fun run(input: String, threadId: String): AgentResult = runBlocking { delegate.run(input, threadId) }

    fun runAsync(input: String): CompletableFuture<AgentResult> = spawn(input).resultAsync()

    fun runAsync(input: String, threadId: String): CompletableFuture<AgentResult> =
        spawn(input, threadId).resultAsync()

    fun stream(input: String): EventStream = EventStream.from(delegate.stream(input))

    fun stream(input: String, threadId: String): EventStream = EventStream.from(delegate.stream(input, threadId))

    fun spawn(input: String): RunHandle = RunHandle.from(delegate.spawn(input))

    fun spawn(input: String, threadId: String): RunHandle = RunHandle.from(delegate.spawn(input, threadId))

    @Throws(InterruptedException::class)
    fun <T> runStructured(input: String, outputType: Class<T>): T =
        runStructured(input, JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(input: String, outputType: TypeReference<T>): T =
        runStructured(input, JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(input: String, outputType: JacksonType<T>): T =
        decodeStructured(runBlocking { delegate.runStructured(input, outputType.outputSpec()) }, outputType)

    @Throws(InterruptedException::class)
    fun <T> runStructured(input: String, threadId: String, outputType: Class<T>): T =
        runStructured(input, threadId, JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(input: String, threadId: String, outputType: TypeReference<T>): T =
        runStructured(input, threadId, JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(input: String, threadId: String, outputType: JacksonType<T>): T =
        decodeStructured(runBlocking { delegate.runStructured(input, outputType.outputSpec(), threadId) }, outputType)

    fun <T> runStructuredAsync(input: String, outputType: Class<T>): CompletableFuture<T> =
        runStructuredAsync(input, JacksonType.of(outputType))

    fun <T> runStructuredAsync(input: String, outputType: TypeReference<T>): CompletableFuture<T> =
        runStructuredAsync(input, JacksonType.of(outputType))

    fun <T> runStructuredAsync(input: String, outputType: JacksonType<T>): CompletableFuture<T> =
        virtualFuture("koaks-structured") {
            decodeStructured(delegate.runStructured(input, outputType.outputSpec()), outputType)
        }

    fun <T> runStructuredAsync(
        input: String,
        threadId: String,
        outputType: Class<T>,
    ): CompletableFuture<T> = runStructuredAsync(input, threadId, JacksonType.of(outputType))

    fun <T> runStructuredAsync(
        input: String,
        threadId: String,
        outputType: TypeReference<T>,
    ): CompletableFuture<T> = runStructuredAsync(input, threadId, JacksonType.of(outputType))

    fun <T> runStructuredAsync(
        input: String,
        threadId: String,
        outputType: JacksonType<T>,
    ): CompletableFuture<T> = virtualFuture("koaks-structured") {
        decodeStructured(delegate.runStructured(input, outputType.outputSpec(), threadId), outputType)
    }

    fun unwrap(): CoreAgent = delegate

    override fun close() = delegate.close()

    class Builder private constructor() {
        private var id: String? = null
        private var name: String = "agent"
        private var instructions: String = ""
        private var model: ModelSpec? = null
        private val tools = mutableListOf<ToolSpec<*>>()
        private val toolContainers = mutableListOf<Any>()
        private var memory: MemoryChoice = MemoryChoice.RuntimeDefault
        private var maxSteps: Int? = null
        private var maxTotalSteps: Int? = null
        private var maxTotalTokens: Int? = null
        private var retry: RetryConfig? = null

        fun id(id: String): Builder = apply {
            require(id.isNotBlank()) { "agent id must not be blank" }
            this.id = id
        }

        fun name(name: String): Builder = apply {
            require(name.isNotBlank()) { "agent name must not be blank" }
            this.name = name
        }

        fun instructions(instructions: String): Builder = apply { this.instructions = instructions }

        fun model(model: ModelSpec): Builder = apply { this.model = model }

        fun tool(tool: ToolSpec<*>): Builder = apply { tools += tool }

        /**
         * Registers all public methods annotated with
         * [org.koaks.framework.annotation.Tool] on [toolContainer].
         */
        fun tool(toolContainer: Any): Builder = apply { toolContainers += toolContainer }

        fun memoryWindow(maxMessages: Int): Builder = apply {
            require(maxMessages > 0) { "memory window must be positive" }
            memory = MemoryChoice.Window(maxMessages)
        }

        fun noMemory(): Builder = apply { memory = MemoryChoice.None }

        fun maxSteps(maxSteps: Int): Builder = apply {
            require(maxSteps > 0) { "maxSteps must be positive" }
            this.maxSteps = maxSteps
        }

        fun runBudget(maxTotalSteps: Int?, maxTotalTokens: Int?): Builder = apply {
            require(maxTotalSteps == null || maxTotalSteps > 0) { "maxTotalSteps must be positive" }
            require(maxTotalTokens == null || maxTotalTokens > 0) { "maxTotalTokens must be positive" }
            this.maxTotalSteps = maxTotalSteps
            this.maxTotalTokens = maxTotalTokens
        }

        fun retryRetriable(maxRetries: Int, delay: Duration): Builder = apply {
            require(maxRetries >= 0) { "maxRetries must not be negative" }
            require(!delay.isNegative) { "retry delay must not be negative" }
            retry = RetryConfig(maxRetries, delay.toMillis())
        }

        fun build(): Agent {
            val agentId = requireNotNull(id) { "agent id is required" }
            val modelSpec = requireNotNull(model) { "model is required" }
            val core = CoreAgentBuilder().apply {
                id = agentId
                name = this@Builder.name
                instructions = this@Builder.instructions
                model { modelSpec.select(this) }
                tools {
                    this@Builder.tools.forEach { tool(it.toCoreTool()) }
                    this@Builder.toolContainers
                        .flatMap(::scanAnnotatedTools)
                        .forEach { tool(it.toCoreTool()) }
                }
                when (val memoryChoice = memory) {
                    MemoryChoice.RuntimeDefault -> Unit
                    MemoryChoice.None -> memory { custom(NoMemoryProvider.id, NoMemoryProvider) }
                    is MemoryChoice.Window -> memory {
                        val provider = WindowMemoryProvider(memoryChoice.maxMessages)
                        custom(provider.id, provider)
                    }
                }
                maxSteps?.let(::terminateAfter)
                if (maxTotalSteps != null || maxTotalTokens != null) runBudget(maxTotalSteps, maxTotalTokens)
                retry?.let { onError(ErrorPolicy.retryRetriable(it.maxRetries, it.delayMillis)) }
            }.build()
            return Agent(core)
        }

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder.create()
    }
}

internal fun <T> decodeStructured(result: AgentResult, outputType: JacksonType<T>): T {
    if (result !is AgentResult.Completed) throw AgentRunException(result)
    val extracted = JsonExtractor.extract(result.text)
    return try {
        outputType.decode(extracted)
    } catch (failure: Throwable) {
        throw StructuredOutputException(result.text, failure)
    }
}

private sealed interface MemoryChoice {
    data object RuntimeDefault : MemoryChoice
    data object None : MemoryChoice
    data class Window(val maxMessages: Int) : MemoryChoice
}

private data class RetryConfig(val maxRetries: Int, val delayMillis: Long)
