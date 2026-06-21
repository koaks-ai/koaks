package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.koaks.framework.memory.AgentThread
import org.koaks.framework.memory.Memory
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.Hook
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.RunBudget
import org.koaks.framework.policy.TerminationPolicy
import org.koaks.framework.tool.ToolRegistry
import org.koaks.framework.transport.Transport

/**
 * An immutable agent: instructions + model + tools + hooks + listeners + termination.
 * Built via the `agent {}` DSL.
 *
 * Implements [AutoCloseable]: when the agent created its own [Transport] (via the
 * DSL), [close] closes it. An externally-injected transport is the caller's to
 * close ([ownsTransport] = false).
 */
class Agent internal constructor(
    val name: String,
    val instructions: Instructions,
    val model: LanguageModel,
    val tools: ToolRegistry,
    val hooks: List<Hook>,
    val listeners: List<AgentListener>,
    val termination: TerminationPolicy,
    val errorPolicy: ErrorPolicy,
    val runBudget: RunBudget,
    private val memory: Memory,
    private val transport: Transport?,
    private val ownsTransport: Boolean,
) : AutoCloseable {

    private val runner = AgentRunner(this)

    /** Streams the agent's events for a single user input. */
    fun stream(input: String): Flow<AgentEvent> = flow {
        // Build inside the flow so any `dynamic { }` instruction segment resolves in
        // this coroutine context, once, before the first model step.
        emitAll(runner.stream(initialMessages(input)))
    }

    /** Runs to terminal state and returns the result. */
    suspend fun run(input: String): AgentResult = runner.run(initialMessages(input))

    /** Runs and produces structured output per [spec] (used by the reified `run<T>`). */
    suspend fun runStructured(input: String, spec: OutputSpec): AgentResult =
        runner.runStructured(initialMessages(input), spec)

    /** Streams from an explicit message list (used by [org.koaks.framework.memory.AgentThread]). */
    internal fun streamMessages(initial: List<Message>): Flow<AgentEvent> = runner.stream(initial)

    /** Prepends the system instructions (if any) to a pre-built message list. */
    internal suspend fun withInstructions(messages: List<Message>): List<Message> = buildList {
        instructions.resolve()?.let { add(Message.system(it)) }
        addAll(messages)
    }

    /** Opens a conversation [org.koaks.framework.memory.AgentThread] backed by this agent's memory. */
    fun thread(id: String): AgentThread =
        AgentThread(this, ThreadId(id))

    /** The agent's configured memory (used by [org.koaks.framework.memory.AgentThread]). */
    internal val memoryStore: Memory get() = memory

    /** Whether any registered tool has external side effects. */
    internal val hasSideEffectingTools: Boolean get() = tools.hasSideEffectingTools()

    /** Builds the initial messages: system instructions (if any) + user input. */
    private suspend fun initialMessages(input: String): List<Message> = buildList {
        instructions.resolve()?.let { add(Message.system(it)) }
        add(Message.user(input))
    }

    /** Builds the model request for the current state. */
    internal fun toRequest(state: AgentState): ChatRequest = ChatRequest(
        messages = state.messages,
        tools = tools.toSchemas(),
        stream = true,
    )

    override fun close() {
        if (ownsTransport) transport?.close()
    }
}

/** Scoped resource form: closes the agent (and its owned transport) on exit. */
inline fun <R> Agent.use(block: (Agent) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
