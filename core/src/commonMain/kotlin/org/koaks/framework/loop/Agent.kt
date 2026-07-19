package org.koaks.framework.loop

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koaks.framework.memory.Memory
import org.koaks.framework.memory.NoMemory
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.TurnCommitBuffer
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.Hook
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.RunBudget
import org.koaks.framework.policy.TerminationPolicy
import org.koaks.framework.tool.ToolRegistry
import org.koaks.framework.transport.Transport

private val logger = KotlinLogging.logger {}

/**
 * An immutable agent: instructions + model + tools + hooks + listeners + termination.
 * Built via the `agent {}` DSL.
 *
 * The agent is stateless and shareable: all conversation state lives in its [Memory],
 * keyed by [ThreadId], so one agent instance can serve many concurrent conversations
 * (and the state can be restored by restoring the memory store, not the agent).
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

    // Guards the one-time "no thread id" warning below.
    private val warnMutex = Mutex()
    private var warnedDefaultThread = false

    /**
     * Streams the agent's events for a single user input.
     *
     * When memory is configured, history for [thread] is loaded before the run and this
     * run's messages are committed on success — the loop itself stays memory-agnostic.
     * If [thread] is null a default thread is used (see [resolveThread]).
     *
     * [context] messages (if any) are inserted after the system instructions / history and
     * before the user [input] — used by runtimes to inject shared context without owning the
     * agent. Context is ephemeral: it is never persisted to memory.
     */
    fun stream(
        input: String,
        thread: String? = null,
        context: List<Message> = emptyList(),
    ): Flow<AgentEvent> = flow {
        if (memory === NoMemory) {
            // Build inside the flow so any `dynamic { }` instruction segment resolves in
            // this coroutine context, once, before the first model step.
            emitAll(runner.stream(initialMessages(input, context)))
            return@flow
        }

        val threadId = resolveThread(thread)
        val history = memory.load(threadId)
        val userMessage = Message.user(input)
        val initial = withInstructions(history + context + userMessage)
        val buffer = TurnCommitBuffer(userMessage)
        var terminalUsage = Usage.ZERO

        try {
            runner.stream(initial).collect { event ->
                buffer.observe(event)
                if (event is AgentEvent.Terminal) terminalUsage = event.usage
                emit(event)
            }
        } finally {
            // Resolve the turn even if the collector was cancelled (a runtime quota preemption
            // or the caller stopping collection): commit on a clean finish, otherwise warn about
            // any discarded side effects. The cancellation that discards the turn must not skip
            // this — hence NonCancellable around the suspending commit/warn.
            withContext(NonCancellable) { commitOrWarn(threadId, buffer, terminalUsage) }
        }
    }

    /** Runs to terminal state and returns the result, loading/committing memory for [thread]. */
    suspend fun run(
        input: String,
        thread: String? = null,
        context: List<Message> = emptyList(),
    ): AgentResult {
        if (memory === NoMemory) return runner.run(initialMessages(input, context))

        val threadId = resolveThread(thread)
        val history = memory.load(threadId)
        val userMessage = Message.user(input)
        val initial = withInstructions(history + context + userMessage)
        val buffer = TurnCommitBuffer(userMessage)

        val events = mutableListOf<AgentEvent>()
        try {
            runner.stream(initial).collect { event ->
                buffer.observe(event)
                events += event
            }
        } finally {
            // See stream(): resolve the turn (commit-or-warn) even under cancellation.
            val terminalUsage = events.filterIsInstance<AgentEvent.Terminal>().lastOrNull()?.usage ?: Usage.ZERO
            withContext(NonCancellable) { commitOrWarn(threadId, buffer, terminalUsage) }
        }

        return events.toAgentResult()
    }

    /** Runs and produces structured output per [spec] (used by the reified `run<T>`). */
    suspend fun runStructured(input: String, spec: OutputSpec): AgentResult =
        runner.runStructured(initialMessages(input), spec)

    /** Prepends the system instructions (if any) to a pre-built message list. */
    internal suspend fun withInstructions(messages: List<Message>): List<Message> = buildList {
        instructions.resolve()?.let { add(Message.system(it)) }
        addAll(messages)
    }

    /** Whether any registered tool has external side effects. */
    internal val hasSideEffectingTools: Boolean get() = tools.hasSideEffectingTools()

    /**
     * Resolves the memory key. An explicit [thread] is used verbatim and silently. A null
     * [thread] with memory configured falls back to [ThreadId.DEFAULT] and logs a one-time
     * warning per agent instance: it works, but callers serving multiple conversations
     * should pass an explicit thread to keep histories isolated.
     */
    private suspend fun resolveThread(thread: String?): ThreadId {
        if (thread != null) return ThreadId(thread)
        warnMutex.withLock {
            if (!warnedDefaultThread) {
                warnedDefaultThread = true
                logger.warn {
                    "agent '$name': memory is configured but no thread id was provided; " +
                        "using ThreadId.DEFAULT ('${ThreadId.DEFAULT.value}'). This is supported but " +
                        "not recommended — pass an explicit thread (e.g. run(input, thread = \"user-1\")) " +
                        "to isolate conversations."
                }
            }
        }
        return ThreadId.DEFAULT
    }

    /** Commits this run's messages on success, else warns about any discarded side effects. */
    private suspend fun commitOrWarn(threadId: ThreadId, buffer: TurnCommitBuffer, usage: Usage) {
        if (buffer.shouldCommit()) {
            // Faithful append of this run's new messages only (history is already persisted).
            // A compressing memory may compact using the API-measured [usage] here.
            memory.commit(threadId, buffer.messagesInOrder(), usage)
        } else {
            warnOnDiscardedSideEffects(threadId, buffer)
        }
    }

    /**
     * `side-effect warning`: when a turn is discarded on failure, any tool with external
     * side effects may already have run, yet leaves no trace in persistent history —
     * risking a duplicate on the next run. We cannot detect that the tool actually ran
     * here (the loop owns that), so we warn whenever the agent exposes any side-effecting
     * tool and a turn was rolled back. Mitigations: idempotency keys, a side-effect ledger,
     * or narrower turn boundaries.
     */
    private fun warnOnDiscardedSideEffects(threadId: ThreadId, buffer: TurnCommitBuffer) {
        if (hasSideEffectingTools && buffer.producedToolResults()) {
            logger.warn {
                "thread ${threadId.value}: a turn with side-effecting tools was rolled back; " +
                    "already-performed side effects are NOT recorded in persistent history and " +
                    "may be repeated on the next run (use an idempotency key / side-effect ledger / " +
                    "narrower turn boundary)"
            }
        }
    }

    /**
     * Builds the initial messages: system instructions (if any) + optional shared
     * [context] + user input.
     */
    private suspend fun initialMessages(
        input: String,
        context: List<Message> = emptyList(),
    ): List<Message> = buildList {
        instructions.resolve()?.let { add(Message.system(it)) }
        addAll(context)
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

/** Reconstructs an [AgentResult] from a collected event stream (memory-backed run path). */
private fun List<AgentEvent>.toAgentResult(): AgentResult {
    when (val terminal = filterIsInstance<AgentEvent.Terminal>().lastOrNull()) {
        is AgentEvent.Completed -> return AgentResult.Completed(terminal.message, terminal.usage)
        is AgentEvent.Terminated -> return AgentResult.Terminated(terminal.message, terminal.usage, terminal.reason)
        null -> {}
    }
    val failed = filterIsInstance<AgentEvent.Failed>().lastOrNull()
    return AgentResult.Failed(
        error = failed?.error
            ?: org.koaks.framework.model.AgentError.ModelError(
                "agent run ended without a terminal event",
                retriable = false,
            ),
        usage = failed?.usage ?: Usage.ZERO,
    )
}

/** Scoped resource form: closes the agent (and its owned transport) on exit. */
inline fun <R> Agent.use(block: (Agent) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
