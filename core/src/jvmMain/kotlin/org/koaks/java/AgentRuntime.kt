package org.koaks.java

import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.java.internal.virtualFuture
import org.koaks.java.json.JacksonType
import org.koaks.runtime.AgentRuntime as CoreAgentRuntime
import org.koaks.runtime.AgentRuntimeConfig
import org.koaks.runtime.resource.Quota
import tools.jackson.core.type.TypeReference

/** Java facade for an explicitly managed Koaks runtime. */
class AgentRuntime private constructor(
    private val delegate: CoreAgentRuntime,
    private val ownsDelegate: Boolean,
) : AutoCloseable {
    val maxConcurrency: Int get() = delegate.maxConcurrency

    @Throws(InterruptedException::class)
    fun run(agent: Agent, input: String): AgentResult = run(agent, input, RunOptions.defaults())

    @Throws(InterruptedException::class)
    fun run(agent: Agent, input: String, options: RunOptions): AgentResult = runBlocking {
        delegate.run(
            agent = agent.unwrap(),
            input = input,
            priority = options.priority,
            quota = options.quotaOrNull(),
            thread = options.coreThreadId(),
            eventDetail = options.eventDetail,
        )
    }

    fun runAsync(agent: Agent, input: String): CompletableFuture<AgentResult> =
        runAsync(agent, input, RunOptions.defaults())

    fun runAsync(agent: Agent, input: String, options: RunOptions): CompletableFuture<AgentResult> =
        spawn(agent, input, options).resultAsync()

    fun stream(agent: Agent, input: String): EventStream =
        stream(agent, input, RunOptions.defaults())

    fun stream(agent: Agent, input: String, options: RunOptions): EventStream = EventStream.from(
        delegate.stream(
            agent = agent.unwrap(),
            input = input,
            priority = options.priority,
            quota = options.quotaOrNull(),
            thread = options.coreThreadId(),
            eventDetail = options.eventDetail,
        ),
    )

    fun spawn(agent: Agent, input: String): RunHandle =
        spawn(agent, input, RunOptions.defaults())

    fun spawn(agent: Agent, input: String, options: RunOptions): RunHandle = RunHandle.from(
        delegate.spawn(
            agent = agent.unwrap(),
            input = input,
            priority = options.priority,
            quota = options.quotaOrNull(),
            thread = options.coreThreadId(),
            eventDetail = options.eventDetail,
        ),
    )

    @Throws(InterruptedException::class)
    fun <T> runStructured(agent: Agent, input: String, outputType: Class<T>): T =
        runStructured(agent, input, RunOptions.defaults(), JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(agent: Agent, input: String, outputType: TypeReference<T>): T =
        runStructured(agent, input, RunOptions.defaults(), JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(agent: Agent, input: String, options: RunOptions, outputType: Class<T>): T =
        runStructured(agent, input, options, JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(agent: Agent, input: String, options: RunOptions, outputType: TypeReference<T>): T =
        runStructured(agent, input, options, JacksonType.of(outputType))

    @Throws(InterruptedException::class)
    fun <T> runStructured(
        agent: Agent,
        input: String,
        options: RunOptions,
        outputType: JacksonType<T>,
    ): T = decodeStructured(
        runBlocking {
            delegate.runStructured(
                agent = agent.unwrap(),
                input = input,
                spec = outputType.outputSpec(),
                priority = options.priority,
                quota = options.quotaOrNull(),
                thread = options.coreThreadId(),
                eventDetail = options.eventDetail,
            )
        },
        outputType,
    )

    fun <T> runStructuredAsync(
        agent: Agent,
        input: String,
        outputType: Class<T>,
    ): CompletableFuture<T> = runStructuredAsync(agent, input, RunOptions.defaults(), JacksonType.of(outputType))

    fun <T> runStructuredAsync(
        agent: Agent,
        input: String,
        outputType: TypeReference<T>,
    ): CompletableFuture<T> = runStructuredAsync(agent, input, RunOptions.defaults(), JacksonType.of(outputType))

    fun <T> runStructuredAsync(
        agent: Agent,
        input: String,
        options: RunOptions,
        outputType: Class<T>,
    ): CompletableFuture<T> = runStructuredAsync(agent, input, options, JacksonType.of(outputType))

    fun <T> runStructuredAsync(
        agent: Agent,
        input: String,
        options: RunOptions,
        outputType: TypeReference<T>,
    ): CompletableFuture<T> = runStructuredAsync(agent, input, options, JacksonType.of(outputType))

    fun <T> runStructuredAsync(
        agent: Agent,
        input: String,
        options: RunOptions,
        outputType: JacksonType<T>,
    ): CompletableFuture<T> = virtualFuture("koaks-runtime-structured") {
        decodeStructured(
            delegate.runStructured(
                agent = agent.unwrap(),
                input = input,
                spec = outputType.outputSpec(),
                priority = options.priority,
                quota = options.quotaOrNull(),
                thread = options.coreThreadId(),
                eventDetail = options.eventDetail,
            ),
            outputType,
        )
    }

    fun unwrap(): CoreAgentRuntime = delegate

    override fun close() {
        if (ownsDelegate) delegate.close()
    }

    class Builder private constructor() {
        private var maxConcurrency: Int = Int.MAX_VALUE
        private var defaultMemoryWindow: Int = 40
        private var maxSteps: Int? = null
        private var maxToolCalls: Int? = null
        private var wallClockTimeout: Duration? = null

        fun maxConcurrency(maxConcurrency: Int): Builder = apply {
            require(maxConcurrency > 0) { "maxConcurrency must be positive" }
            this.maxConcurrency = maxConcurrency
        }

        fun defaultMemoryWindow(maxMessages: Int): Builder = apply {
            require(maxMessages > 0) { "default memory window must be positive" }
            defaultMemoryWindow = maxMessages
        }

        fun defaultQuota(maxSteps: Int?, maxToolCalls: Int?, wallClockTimeout: Duration?): Builder = apply {
            require(maxSteps == null || maxSteps > 0) { "maxSteps must be positive" }
            require(maxToolCalls == null || maxToolCalls > 0) { "maxToolCalls must be positive" }
            require(wallClockTimeout == null || (!wallClockTimeout.isZero && !wallClockTimeout.isNegative)) {
                "wallClockTimeout must be positive"
            }
            this.maxSteps = maxSteps
            this.maxToolCalls = maxToolCalls
            this.wallClockTimeout = wallClockTimeout
        }

        fun build(): AgentRuntime {
            val config = AgentRuntimeConfig().also {
                it.maxConcurrency = maxConcurrency
                it.defaultMemoryProvider = WindowMemoryProvider(defaultMemoryWindow)
                it.defaultQuota = Quota(maxSteps, maxToolCalls, wallClockTimeout?.toMillis())
            }
            return AgentRuntime(CoreAgentRuntime(config), ownsDelegate = true)
        }

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder.create()

        @JvmStatic
        fun defaultRuntime(): AgentRuntime = AgentRuntime(CoreAgentRuntime.default, ownsDelegate = false)

        @JvmStatic
        fun shutdownDefault() = CoreAgentRuntime.shutdownDefault()
    }
}
