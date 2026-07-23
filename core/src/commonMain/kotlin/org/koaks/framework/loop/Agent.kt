package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.Hook
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.RunBudget
import org.koaks.framework.policy.TerminationPolicy
import org.koaks.framework.skill.SkillDescriptor
import org.koaks.framework.tool.ToolRegistry
import org.koaks.framework.transport.Transport
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.acb.AgentHandle

/**
 * Immutable Agent definition. Every public execution method delegates to an
 * [AgentRuntime]; the loop-only execution hooks are internal and used by the runtime.
 */
class Agent internal constructor(
    val id: AgentId,
    val name: String,
    val instructions: Instructions,
    val model: LanguageModel,
    val tools: ToolRegistry,
    val hooks: List<Hook>,
    val listeners: List<AgentListener>,
    val termination: TerminationPolicy,
    val errorPolicy: ErrorPolicy,
    val runBudget: RunBudget,
    private val preparation: AgentPreparation,
    internal val memoryProvider: MemoryProvider?,
    private val transport: Transport?,
    private val ownsTransport: Boolean,
) : AutoCloseable {

    internal val definitionUid: Long = AgentDefinitionIds.next()

    private val runner = AgentRunner(this)

    /** Descriptors of the fixed Skills loaded by [prepare]; empty before preparation. */
    val skillDescriptors: List<SkillDescriptor>
        get() = preparation.skillDescriptors

    /**
     * Resolves Skill sources and lazy tools exactly once, validates all contributions,
     * and fixes the effective Skill contributions before the first model call.
     */
    suspend fun prepare() {
        preparation.await()
    }

    /** Runtime-managed streaming execution using the process-wide default runtime. */
    fun stream(input: String, thread: ThreadId? = null): Flow<AgentEvent> =
        AgentRuntime.default.stream(this, input, thread = thread)

    fun stream(input: String, thread: String): Flow<AgentEvent> =
        stream(input, ThreadId(thread))

    /** Runtime-managed foreground execution using the process-wide default runtime. */
    suspend fun run(input: String, thread: ThreadId? = null): AgentResult =
        AgentRuntime.default.run(this, input, thread = thread)

    suspend fun run(input: String, thread: String): AgentResult =
        run(input, ThreadId(thread))

    /** Runtime-managed background execution using the process-wide default runtime. */
    fun spawn(input: String, thread: ThreadId? = null): AgentHandle =
        AgentRuntime.default.spawn(this, input, thread = thread)

    fun spawn(input: String, thread: String): AgentHandle =
        spawn(input, ThreadId(thread))

    internal fun executeStream(input: String, context: List<Message>): Flow<AgentEvent> = flow {
        emitAll(runner.stream(initialMessages(input, context)))
    }

    internal fun executeStructuredStream(input: String, context: List<Message>, spec: OutputSpec): Flow<AgentEvent> = flow {
        emitAll(runner.streamStructured(initialMessages(input, context), spec))
    }

    suspend fun runStructured(input: String, spec: OutputSpec, thread: ThreadId? = null): AgentResult =
        AgentRuntime.default.runStructured(this, input, spec, thread = thread)

    suspend fun runStructured(input: String, spec: OutputSpec, thread: String): AgentResult =
        runStructured(input, spec, ThreadId(thread))

    /** Builds system instructions + runtime-owned history/context + current user input. */
    private suspend fun initialMessages(input: String, context: List<Message>): List<Message> = buildList {
        preparation.await().instructions.resolve()?.let { add(Message.system(it)) }
        addAll(context)
        add(Message.user(input))
    }

    internal fun toRequest(state: AgentState): ChatRequest = ChatRequest(
        messages = state.messages,
        tools = tools.toSchemas(),
        stream = true,
    )

    override fun close() {
        if (ownsTransport) transport?.close()
    }
}

private object AgentDefinitionIds {
    private val sequence = MutableStateFlow(0L)
    fun next(): Long = sequence.getAndUpdate { it + 1 }
}

inline fun <R> Agent.use(block: (Agent) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
