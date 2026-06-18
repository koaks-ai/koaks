package org.koaks.framework.loop

import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.AgentMiddleware
import org.koaks.framework.memory.Memory
import org.koaks.framework.memory.NoMemory
import org.koaks.framework.memory.WindowMemory
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.RunBudget
import org.koaks.framework.policy.TerminationPolicy
import org.koaks.framework.tool.ToolRegistry

/**
 * Builder backing the `agent { }` DSL. A thin sugar over the [Agent] constructor —
 * it only assembles the immutable object, holding no logic itself.
 */
@AgentDSL
class AgentBuilder {
    var name: String = "agent"
    var instructions: String? = null

    private var modelScope: ModelScope? = null
    private var selection: ModelSelection? = null
    private val tools = ToolRegistry()
    private val middlewares = mutableListOf<AgentMiddleware>()
    private val listeners = mutableListOf<AgentListener>()
    private var termination: TerminationPolicy = TerminationPolicy.maxSteps(10)
    private var errorPolicy: ErrorPolicy = ErrorPolicy.PROPAGATE
    private var memory: Memory = NoMemory
    private var runBudget: RunBudget = RunBudget.UNLIMITED

    fun model(block: ModelScope.() -> ModelSelection) {
        val scope = ModelScope()
        selection = scope.block()
        modelScope = scope
    }

    fun tools(block: ToolScope.() -> Unit) {
        ToolScope(tools).apply(block)
    }

    /** Configures conversation memory: `memory { window(40) }` / `none()` / `custom(...)`. */
    fun memory(block: MemoryScope.() -> Unit) {
        memory = MemoryScope().apply(block).build()
    }

    /** Installs around-style middleware. */
    fun install(middleware: AgentMiddleware) {
        middlewares += middleware
    }

    /** Installs a push-style listener (e.g. Tracing). */
    fun install(listener: AgentListener) {
        listeners += listener
    }

    fun terminateAfter(maxSteps: Int) {
        termination = TerminationPolicy.maxSteps(maxSteps)
    }

    fun terminate(policy: TerminationPolicy) {
        termination = policy
    }

    /** Sets the whole-run global guard: caps total steps / tokens across the run. */
    fun runBudget(maxTotalSteps: Int? = null, maxTotalTokens: Int? = null) {
        runBudget = RunBudget(maxTotalSteps, maxTotalTokens)
    }

    fun onError(policy: ErrorPolicy) {
        errorPolicy = policy
    }

    internal fun build(): Agent {
        val scope = requireNotNull(modelScope) { "model { } block is required" }
        val model = requireNotNull(selection) {
            "a model is required (e.g. model { qwen(...) })"
        }.toModel()
        val transportInfo = scope.transportInfo()
        return Agent(
            name = name,
            instructions = instructions,
            model = model,
            tools = tools,
            middlewares = middlewares.toList(),
            listeners = listeners.toList(),
            termination = termination,
            errorPolicy = errorPolicy,
            runBudget = runBudget,
            memory = memory,
            transport = transportInfo.transport,
            ownsTransport = transportInfo.ownsTransport,
        )
    }
}

/** Top-level entry point: `val a = agent { ... }`. */
fun agent(block: AgentBuilder.() -> Unit): Agent = AgentBuilder().apply(block).build()

/** Alias for `agent { ... }` to improve readability at the call site. E.g. `createAgent { ... }`. */
fun createAgent(block: AgentBuilder.() -> Unit): Agent = agent(block)

/** DSL scope for selecting conversation memory. */
@AgentDSL
class MemoryScope {
    private var memory: Memory = NoMemory

    /** Sliding-window memory with turn-atomic trimming (load-side). */
    fun window(maxMessages: Int) {
        memory = WindowMemory(maxMessages)
    }

    /** No persistence (the default). */
    fun none() {
        memory = NoMemory
    }

    /** Plug in a custom [Memory] implementation (e.g. summarizing / vector). */
    fun custom(memory: Memory) {
        this.memory = memory
    }

    internal fun build(): Memory = memory
}
