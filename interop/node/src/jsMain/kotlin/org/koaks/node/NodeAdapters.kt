package org.koaks.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.loop.AgentBuilder
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.loop.toLanguageModel
import org.koaks.framework.mcp.McpToolGateway
import org.koaks.framework.mcp.client.DefaultMcpClient
import org.koaks.framework.mcp.entity.McpClientConfig
import org.koaks.framework.mcp.entity.McpTool
import org.koaks.framework.memory.FixedMemoryProvider
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.NoMemoryProvider
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.TurnRetention
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.Hook
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.middleware.ToolDecision
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ClientActionHandler
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.Recovery
import org.koaks.framework.policy.SuspendErrorPolicy
import org.koaks.framework.policy.SuspendTerminationPolicy
import org.koaks.framework.policy.TerminationDecision
import org.koaks.framework.policy.TerminationPolicy
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.skill.SkillDefinition
import org.koaks.framework.skill.SkillDescriptor
import org.koaks.framework.skill.SkillId
import org.koaks.framework.skill.SkillLoader
import org.koaks.framework.skill.SkillResource
import org.koaks.framework.skill.SkillResourceCursor
import org.koaks.framework.skill.SkillResourceProvider
import org.koaks.framework.tool.Tool
import org.koaks.framework.tool.ContextualTool
import org.koaks.framework.tool.ToolInvocationContext
import org.koaks.framework.tool.ToolOutcome
import org.koaks.framework.transport.KtorTransport
import org.koaks.memory.summarizing.SummarizingMemoryProvider
import org.koaks.memory.summarizing.InMemoryAppendMemoryProvider
import org.koaks.memory.summarizing.InMemorySummaryStateStore
import org.koaks.memory.summarizing.SummaryStateStore
import org.koaks.memory.summarizing.SummaryCheckpoint
import org.koaks.memory.summarizing.CompactionEvent
import org.koaks.memory.summarizing.CompactionObserver
import org.koaks.framework.model.TranscriptBasis
import org.koaks.memory.vector.VectorMemoryProvider
import org.koaks.memory.vector.VectorStore
import org.koaks.provider.anthropic.anthropic
import org.koaks.provider.ollama.ollama
import org.koaks.provider.openai.openai
import org.koaks.provider.openai.responses.ResponsesStateMode
import org.koaks.provider.openai.responses.openaiResponses
import org.koaks.provider.qwen.qwen
import org.koaks.runtime.resource.RuntimeContext
import kotlin.js.Promise

internal class CallbackGateway(
    private val invoke: (String, String) -> Promise<String>,
    private val notify: (String, String) -> Unit,
) {
    suspend fun call(id: String, payload: JsonElement = JsonNull): JsonElement {
        val response = invoke(id, nodeJson.encodeToString(JsonElement.serializer(), payload)).await()
        if (response.isBlank()) return JsonNull
        val decoded = nodeJson.parseToJsonElement(response)
        val envelope = decoded as? JsonObject ?: return decoded
        val ok = envelope.booleanOrNull("ok") ?: return decoded
        if (ok) return envelope["value"] ?: JsonNull
        val error = envelope.objectOrNull("error") ?: JsonObject(emptyMap())
        throw NodeCallbackException(
            callbackType = error.stringOrNull("type") ?: "callback_error",
            message = error.stringOrNull("message") ?: "Node callback failed",
            callbackStack = error.stringOrNull("stack"),
        )
    }

    fun emit(id: String?, payload: JsonElement) {
        if (id != null) runCatching {
            notify(id, nodeJson.encodeToString(JsonElement.serializer(), payload))
        }
    }
}

internal data class BuiltAgent(
    val agent: org.koaks.framework.loop.Agent,
    val owned: List<AutoCloseable>,
)

internal fun buildAgent(
    config: JsonObject,
    callbacks: CallbackGateway,
    toolExecutions: ToolExecutionRegistry,
): BuiltAgent {
    val owned = mutableListOf<AutoCloseable>()
    val built = org.koaks.framework.loop.agent {
        id = config.string("id")
        name = config.stringOrNull("name") ?: config.string("id")
        configureInstructions(config["instructions"], callbacks)
        model {
            val models = when (val element = config["model"] ?: error("'model' is required")) {
                is JsonArray -> element.map { it.jsonObject }
                is JsonObject -> listOf(element)
                else -> error("'model' must be an object or non-empty array")
            }
            require(models.isNotEmpty()) { "'model' must not be empty" }
            models.map { selectProvider(it) }.reduce(ModelSelection::fallback)
        }
        config.arrayOrNull("tools")?.let { definitions ->
            tools { definitions.forEach { tool(NodeTool(it.jsonObject, callbacks, toolExecutions)) } }
        }
        config.objectOrNull("memory")?.let { memoryConfig ->
            val memory = buildMemory(memoryConfig, callbacks, owned)
            memory { custom(memory.id, memory) }
        }
        config.objectOrNull("skills")?.let { skillConfig ->
            skills {
                skillConfig.arrayOrNull("sources").orEmpty().forEach { source ->
                    val obj = source.jsonObject
                    when (obj.string("type")) {
                        "directory" -> source(obj.string("path"))
                        "loader" -> source(NodeSkillLoader(obj, callbacks, toolExecutions))
                        else -> error("unknown skill source type '${obj.string("type")}'")
                    }
                }
                skillConfig.arrayOrNull("use").orEmpty().forEach { use(it.jsonPrimitive.content) }
            }
        }
        config.arrayOrNull("mcp")?.let { gateways ->
            tools {
                gateways.forEach { entry ->
                    val obj = entry.jsonObject
                    val gateway: McpToolGateway = when (obj.string("type")) {
                        "gateway" -> NodeMcpGateway(obj, callbacks)
                        "http" -> DefaultMcpClient(
                            mcpUrl = obj.string("url"),
                            mcpClientConfig = McpClientConfig(id = obj.intOrNull("client_id") ?: 1),
                            customHeaders = obj.objectOrNull("headers")?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                        ).also(owned::add)
                        else -> error("unknown MCP type '${obj.string("type")}'")
                    }
                    mcp(gateway)
                }
            }
        }
        config.arrayOrNull("hooks").orEmpty().forEach { install(NodeHook(it.jsonObject, callbacks)) }
        config.arrayOrNull("listeners").orEmpty().forEach { install(NodeListener(it.jsonObject, callbacks)) }
        config.arrayOrNull("client_actions").orEmpty().forEach {
            clientAction(NodeClientActionHandler(it.jsonObject.string("callback_id"), callbacks))
        }
        config.objectOrNull("termination")?.let { termination ->
            val callback = termination.stringOrNull("callback_id")
            if (callback == null) terminate(buildTerminationPolicy(termination))
            else terminateAsync(buildSuspendTerminationPolicy(callback, callbacks))
        }
        config.objectOrNull("run_budget")?.let { budget ->
            runBudget(budget.intOrNull("max_total_steps"), budget.intOrNull("max_total_tokens"))
        }
        config.objectOrNull("error_policy")?.let {
            if (it.string("type") == "custom") onErrorAsync(buildSuspendErrorPolicy(it.string("callback_id"), callbacks))
            else onError(buildErrorPolicy(it))
        }
    }
    return BuiltAgent(built, owned)
}

private fun AgentBuilder.configureInstructions(value: JsonElement?, callbacks: CallbackGateway) {
    when (value) {
        null, JsonNull -> Unit
        is JsonPrimitive -> instructions = value.content
        is JsonArray -> instructions {
            value.forEach { entry ->
                val obj = entry.jsonObject
                when (obj.string("type")) {
                    "static" -> text(obj.string("text"))
                    "dynamic" -> dynamic {
                        callbacks.call(obj.string("callback_id"), buildJsonObject { put("type", JsonPrimitive("resolve_instructions")) })
                            .let { if (it is JsonNull) null else it.jsonPrimitive.content }
                    }
                    else -> error("unknown instruction segment '${obj.string("type")}'")
                }
            }
        }
        else -> error("'instructions' must be a string or array")
    }
}

internal fun ModelScope.selectProvider(config: JsonObject): ModelSelection = when (config.string("type")) {
    "openai" -> openai(
        baseUrl = config.stringOrNull("base_url") ?: org.koaks.provider.openai.OPENAI_DEFAULT_BASE_URL,
        apiKey = config.string("api_key"),
        modelName = config.string("model"),
    ) {
        temperature = config.doubleOrNull("temperature")
        maxCompletionTokens = config.intOrNull("max_completion_tokens")
        topP = config.doubleOrNull("top_p")
        stop = config.arrayOrNull("stop")?.map { it.jsonPrimitive.content }
        presencePenalty = config.doubleOrNull("presence_penalty")
        frequencyPenalty = config.doubleOrNull("frequency_penalty")
        reasoningEffort = config.stringOrNull("reasoning_effort")
        config.longOrNull("stream_idle_timeout_ms")?.let { streamIdleTimeoutMs = it }
        applyCapabilities(config.objectOrNull("capabilities")) { parallel, vision, jsonObject, jsonSchema ->
            capabilities { parallelToolCalls = parallel; this.vision = vision; jsonMode = jsonObject; this.jsonSchema = jsonSchema }
        }
    }
    "openai_responses", "openai-responses" -> openaiResponses(
        baseUrl = config.stringOrNull("base_url") ?: org.koaks.provider.openai.OPENAI_DEFAULT_BASE_URL,
        apiKey = config.string("api_key"),
        modelName = config.string("model"),
    ) {
        temperature = config.doubleOrNull("temperature"); topP = config.doubleOrNull("top_p"); maxOutputTokens = config.intOrNull("max_output_tokens")
        reasoning = config.objectOrNull("reasoning"); truncation = config.stringOrNull("truncation"); background = config.booleanOrNull("background")
        config.longOrNull("background_poll_interval_ms")?.let { backgroundPollIntervalMs = it }
        config.longOrNull("stream_idle_timeout_ms")?.let { streamIdleTimeoutMs = it }
        stateMode = when (config.stringOrNull("state_mode")) { "server_stored" -> ResponsesStateMode.ServerStored; "conversation" -> ResponsesStateMode.Conversation; else -> ResponsesStateMode.Replayable }
        persistCheckpoint = config.booleanOrNull("persist_checkpoint") ?: false
        include = config.arrayOrNull("include")?.map { it.jsonPrimitive.content }
        config.arrayOrNull("server_tools").orEmpty().forEach { tool ->
            val obj = tool.jsonObject
            when (obj.string("type")) {
                "web_search" -> webSearch(obj.stringOrNull("search_context_size"))
                "file_search" -> fileSearch(*obj.arrayOrNull("vector_store_ids").orEmpty().map { it.jsonPrimitive.content }.toTypedArray())
                "code_interpreter" -> codeInterpreter(obj.stringOrNull("container"))
            }
        }
        applyCapabilities(config.objectOrNull("capabilities")) { parallel, vision, jsonObject, jsonSchema ->
            capabilities { parallelToolCalls = parallel; this.vision = vision; jsonMode = jsonObject; this.jsonSchema = jsonSchema }
        }
    }
    "qwen" -> qwen(
        baseUrl = config.stringOrNull("base_url") ?: org.koaks.provider.qwen.QWEN_DEFAULT_BASE_URL,
        apiKey = config.string("api_key"), modelName = config.string("model"),
    ) {
        temperature = config.doubleOrNull("temperature"); maxTokens = config.intOrNull("max_tokens"); topP = config.doubleOrNull("top_p")
        stop = config.arrayOrNull("stop")?.map { it.jsonPrimitive.content }; presencePenalty = config.doubleOrNull("presence_penalty")
        frequencyPenalty = config.doubleOrNull("frequency_penalty"); enableThinking = config.booleanOrNull("enable_thinking")
        config.longOrNull("stream_idle_timeout_ms")?.let { streamIdleTimeoutMs = it }
        applyCapabilities(config.objectOrNull("capabilities")) { parallel, vision, jsonObject, _ ->
            capabilities { parallelToolCalls = parallel; this.vision = vision; jsonMode = jsonObject }
        }
    }
    "anthropic" -> anthropic(
        baseUrl = config.stringOrNull("base_url") ?: org.koaks.provider.anthropic.ANTHROPIC_DEFAULT_BASE_URL,
        apiKey = config.string("api_key"), modelName = config.string("model"),
    ) {
        config.intOrNull("max_tokens")?.let { maxTokens = it }; temperature = config.doubleOrNull("temperature"); topP = config.doubleOrNull("top_p")
        topK = config.intOrNull("top_k"); stopSequences = config.arrayOrNull("stop_sequences")?.map { it.jsonPrimitive.content }; thinking = config.objectOrNull("thinking")
        config.stringOrNull("anthropic_version")?.let { anthropicVersion = it }; config.longOrNull("stream_idle_timeout_ms")?.let { streamIdleTimeoutMs = it }
        val caps = config.objectOrNull("capabilities")
        if (caps != null) capabilities {
            caps.booleanOrNull("parallel_tool_calls")?.let { parallelToolCalls = it }; caps.booleanOrNull("vision")?.let { vision = it }
            caps.booleanOrNull("json_object")?.let { jsonMode = it }; caps.booleanOrNull("assistant_prefill")?.let { assistantPrefill = it }
        }
    }
    "ollama" -> ollama(
        baseUrl = config.string("base_url"), apiKey = config.stringOrNull("api_key") ?: "ollama", modelName = config.string("model"),
    ) {
        temperature = config.doubleOrNull("temperature"); topP = config.doubleOrNull("top_p"); maxTokens = config.intOrNull("max_tokens")
        stop = config.arrayOrNull("stop")?.map { it.jsonPrimitive.content }; think = config.booleanOrNull("think")
        config.longOrNull("stream_idle_timeout_ms")?.let { streamIdleTimeoutMs = it }
        applyCapabilities(config.objectOrNull("capabilities")) { parallel, vision, jsonObject, _ ->
            capabilities { parallelToolCalls = parallel; this.vision = vision; jsonMode = jsonObject }
        }
    }
    else -> error("unknown provider type '${config.string("type")}'")
}

private inline fun applyCapabilities(
    caps: JsonObject?,
    block: (Boolean, Boolean, Boolean, Boolean) -> Unit,
) {
    if (caps == null) return
    block(
        caps.booleanOrNull("parallel_tool_calls") ?: true,
        caps.booleanOrNull("vision") ?: false,
        caps.booleanOrNull("json_object") ?: false,
        caps.booleanOrNull("json_schema") ?: false,
    )
}

private class NodeTool(
    config: JsonObject,
    private val callbacks: CallbackGateway,
    private val toolExecutions: ToolExecutionRegistry,
) : ContextualTool<String> {
    override val name: String = config.string("name")
    override val description: String = config.stringOrNull("description") ?: ""
    override val inputSerializer = String.serializer()
    override val parametersOverride: JsonObject = config.objectOrNull("input_schema") ?: JsonObject(emptyMap())
    override val acceptsRawJson: Boolean = true
    override val returnDirectly: Boolean = config.booleanOrNull("return_directly") ?: false
    override val hasSideEffects: Boolean = config.booleanOrNull("has_side_effects") ?: false
    private val executeCallback = config.string("execute_callback_id")
    private val cancelCallback = config.stringOrNull("cancel_callback_id")

    override suspend fun execute(input: String): String = executeNode(input, invocation = null)

    override suspend fun execute(input: String, context: ToolInvocationContext): String = executeNode(input, context)

    private suspend fun executeNode(input: String, invocation: ToolInvocationContext?): String {
        val coroutineContext = currentCoroutineContext()
        val runtimeContext = coroutineContext[RuntimeContext]
            ?: throw NodeBridgeException("lifecycle_error", "JS Tools require a runtime-managed execution")
        val executionContext = coroutineContext[AgentExecutionContext]
            ?: throw NodeBridgeException("lifecycle_error", "JS Tools require an Agent execution branch")
        val execution = toolExecutions.open(runtimeContext, executionContext, invocation)
        return try {
            val response = executionContext.waiting {
                callbacks.call(
                    executeCallback,
                    buildJsonObject {
                        put("execution_id", JsonPrimitive(execution.id))
                        put("call_id", JsonPrimitive(invocation?.callId ?: execution.id))
                        put("tool_name", JsonPrimitive(name))
                        put("arguments_json", JsonPrimitive(input))
                        put("run_id", JsonPrimitive(runtimeContext.runId.value.toString()))
                        put("agent_id", JsonPrimitive(runtimeContext.agentId.value))
                        runtimeContext.threadId?.let { put("thread_id", JsonPrimitive(it.value)) }
                        runtimeContext.turnId?.let { put("turn_id", JsonPrimitive(it.value.toString())) }
                        runtimeContext.correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
                    },
                )
            }.jsonObject
            response.string("output")
        } catch (cancelled: CancellationException) {
            callbacks.emit(cancelCallback, buildJsonObject {
                put("execution_id", JsonPrimitive(execution.id))
                put("reason", JsonPrimitive(cancelled.message ?: "cancelled"))
            })
            throw cancelled
        } finally {
            toolExecutions.close(execution.id)
        }
    }
}

internal fun buildMemory(config: JsonObject, callbacks: CallbackGateway, owned: MutableList<AutoCloseable>): MemoryProvider = when (config.string("type")) {
    "none" -> NoMemoryProvider
    "window" -> WindowMemoryProvider(config.intOrNull("max_messages") ?: 40, config.retention())
    "custom" -> FixedMemoryProvider(MemoryProviderId(config.string("id"))) { thread ->
        val opened = callbacks.call(config.string("open_callback_id"), buildJsonObject { put("thread_id", JsonPrimitive(thread.value)) }).jsonObject
        NodeThreadMemory(opened, callbacks)
    }
    "vector" -> VectorMemoryProvider(
        id = MemoryProviderId(config.string("id")),
        store = NodeVectorStore(config, callbacks),
        topK = config.intOrNull("top_k") ?: 8,
        retention = config.retention(),
    )
    "summarizing" -> {
        val transport = KtorTransport().also(owned::add)
        val scope = ModelScope().apply { transport(transport) }
        val model = scope.selectProvider(config.objectOrNull("model") ?: error("summarizing memory requires 'model'"))
            .toLanguageModel()
        val id = MemoryProviderId(config.string("id"))
        val delegate = config.objectOrNull("delegate")?.let { buildMemory(it, callbacks, owned) }
            ?: InMemoryAppendMemoryProvider(MemoryProviderId("${id.value}-raw"), config.retention())
        val stateStore: SummaryStateStore = if (config.stringOrNull("state_load_callback_id") == null) {
            InMemorySummaryStateStore()
        } else {
            NodeSummaryStateStore(config, callbacks)
        }
        val observer = config.stringOrNull("compaction_callback_id")?.let { callback ->
            CompactionObserver { event -> callbacks.emit(callback, event.toJson()) }
        } ?: CompactionObserver {}
        SummarizingMemoryProvider(
            id = id,
            delegate = delegate,
            stateStore = stateStore,
            model = model,
            maxTokens = config.intOrNull("max_tokens") ?: error("summarizing memory requires 'max_tokens'"),
            keepRecentTurns = config.intOrNull("keep_recent_turns") ?: 2,
            retention = config.retention(),
            observer = observer,
        )
    }
    else -> error("unknown memory type '${config.string("type")}'")
}

private fun JsonObject.retention(): TurnRetention = when (stringOrNull("retention")) {
    "completed_only" -> TurnRetention.CompletedOnly
    "interrupted_if_side_effects" -> TurnRetention.InterruptedIfSideEffects
    else -> TurnRetention.Interrupted
}

private class NodeThreadMemory(config: JsonObject, private val callbacks: CallbackGateway) : ThreadMemory {
    private val loadCallback = config.string("load_callback_id")
    private val commitCallback = config.string("commit_callback_id")
    private val closeCallback = config.stringOrNull("close_callback_id")
    override val retention: TurnRetention = config.retention()

    override suspend fun load(query: List<ModelItem>) = callbacks.call(
        loadCallback,
        buildJsonObject { put("query", buildJsonArray { query.forEach { add(it.toJson()) } }) },
    ).jsonObject.toMemoryView()

    override suspend fun commit(turn: org.koaks.framework.memory.ConversationTurn) {
        callbacks.call(commitCallback, turn.toJson())
    }

    override fun close() {
        callbacks.emit(closeCallback, JsonNull)
    }
}

private class NodeSummaryStateStore(config: JsonObject, private val callbacks: CallbackGateway) : SummaryStateStore {
    private val loadCallback = config.string("state_load_callback_id")
    private val saveCallback = config.string("state_save_callback_id")
    private val deleteCallback = config.string("state_delete_callback_id")

    override suspend fun load(threadId: ThreadId): SummaryCheckpoint? = callbacks.call(
        loadCallback,
        buildJsonObject { put("thread_id", JsonPrimitive(threadId.value)) },
    ).let { if (it is JsonNull) null else it.jsonObject.toSummaryCheckpoint() }

    override suspend fun save(threadId: ThreadId, checkpoint: SummaryCheckpoint) {
        callbacks.call(saveCallback, buildJsonObject {
            put("thread_id", JsonPrimitive(threadId.value)); put("checkpoint", checkpoint.toJson())
        })
    }

    override suspend fun delete(threadId: ThreadId) {
        callbacks.call(deleteCallback, buildJsonObject { put("thread_id", JsonPrimitive(threadId.value)) })
    }
}

private fun SummaryCheckpoint.toJson(): JsonObject = buildJsonObject {
    put("basis", buildJsonObject {
        put("item_count", JsonPrimitive(basis.itemCount)); put("digest", JsonPrimitive(basis.digest))
    })
    put("summary", summary.toJson()); put("source_turn_id", JsonPrimitive(sourceTurnId))
    put("created_at_epoch_ms", JsonPrimitive(createdAtEpochMillis))
}

private fun JsonObject.toSummaryCheckpoint(): SummaryCheckpoint {
    val basis = objectOrNull("basis") ?: error("summary checkpoint requires 'basis'")
    val summary = objectOrNull("summary")?.toModelItem() as? ModelItem.Message
        ?: error("summary checkpoint requires a message 'summary'")
    return SummaryCheckpoint(
        basis = TranscriptBasis(basis.intOrNull("item_count") ?: error("basis requires item_count"), basis.string("digest")),
        summary = summary,
        sourceTurnId = string("source_turn_id"),
        createdAtEpochMillis = longOrNull("created_at_epoch_ms") ?: error("checkpoint requires created_at_epoch_ms"),
    )
}

private fun CompactionEvent.toJson(): JsonObject = buildJsonObject {
    put("thread_id", JsonPrimitive(threadId.value)); put("source_turn_id", JsonPrimitive(sourceTurnId))
    when (this@toJson) {
        is CompactionEvent.Started -> {
            put("type", JsonPrimitive("started")); put("basis", buildJsonObject {
                put("item_count", JsonPrimitive(basis.itemCount)); put("digest", JsonPrimitive(basis.digest))
            })
        }
        is CompactionEvent.Completed -> { put("type", JsonPrimitive("completed")); put("checkpoint", checkpoint.toJson()) }
        is CompactionEvent.Failed -> { put("type", JsonPrimitive("failed")); put("message", JsonPrimitive(message)) }
    }
}

private class NodeVectorStore(config: JsonObject, private val callbacks: CallbackGateway) : VectorStore {
    private val addCallback = config.string("add_callback_id")
    private val searchCallback = config.string("search_callback_id")

    override suspend fun add(threadId: String, items: List<ModelItem>) {
        callbacks.call(addCallback, buildJsonObject {
            put("thread_id", JsonPrimitive(threadId)); put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
        })
    }

    override suspend fun search(threadId: String, query: String, topK: Int): List<ModelItem> = callbacks.call(
        searchCallback,
        buildJsonObject { put("thread_id", JsonPrimitive(threadId)); put("query", JsonPrimitive(query)); put("top_k", JsonPrimitive(topK)) },
    ).let { it as JsonArray }.map { it.jsonObject.toModelItem() }
}

private class NodeMcpGateway(config: JsonObject, private val callbacks: CallbackGateway) : McpToolGateway {
    private val listCallback = config.string("list_tools_callback_id")
    private val callCallback = config.string("call_tool_callback_id")
    override suspend fun listTools(): List<McpTool> = (callbacks.call(listCallback) as JsonArray).map {
        val obj = it.jsonObject
        McpTool(obj.string("name"), obj.stringOrNull("description") ?: "", obj.objectOrNull("input_schema"))
    }
    override suspend fun callTool(name: String, argumentsJson: String): String = callbacks.call(
        callCallback,
        buildJsonObject { put("name", JsonPrimitive(name)); put("arguments_json", JsonPrimitive(argumentsJson)) },
    ).jsonObject.string("output")
}

private class NodeSkillLoader(
    config: JsonObject,
    private val callbacks: CallbackGateway,
    private val toolExecutions: ToolExecutionRegistry,
) : SkillLoader {
    private val discoverCallback = config.string("discover_callback_id")
    private val loadCallback = config.string("load_callback_id")
    override suspend fun discover(): List<SkillDescriptor> = (callbacks.call(discoverCallback) as JsonArray).map { it.jsonObject.toSkillDescriptor() }
    override suspend fun load(id: SkillId): SkillDefinition {
        val loaded = callbacks.call(loadCallback, buildJsonObject { put("id", JsonPrimitive(id.value)) }).jsonObject
        val descriptor = loaded.objectOrNull("descriptor")?.toSkillDescriptor() ?: SkillDescriptor(id, loaded.string("description"))
        val resourceCallback = loaded.stringOrNull("resource_callback_id")
        return SkillDefinition(
            descriptor = descriptor,
            instructions = loaded.string("instructions"),
            resources = resourceCallback?.let { callback -> NodeSkillResources(callback, callbacks) },
            tools = loaded.arrayOrNull("tools").orEmpty().map { NodeTool(it.jsonObject, callbacks, toolExecutions) },
        )
    }
}

private fun JsonObject.toSkillDescriptor() = SkillDescriptor(
    id = SkillId(string("id")),
    description = string("description"),
    metadata = objectOrNull("metadata")?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
)

private class NodeSkillResources(private val callback: String, private val callbacks: CallbackGateway) : SkillResourceProvider {
    override suspend fun read(request: org.koaks.framework.skill.SkillResourceRequest): SkillResource {
        val response = callbacks.call(callback, buildJsonObject {
            put("path", JsonPrimitive(request.path)); put("line", JsonPrimitive(request.cursor.line)); put("column", JsonPrimitive(request.cursor.column))
            put("max_lines", JsonPrimitive(request.maxLines)); put("max_chars", JsonPrimitive(request.maxChars))
        }).jsonObject
        return SkillResource(
            path = response.string("path"), content = response.string("content"), firstLine = response.intOrNull("first_line")!!,
            lastLine = response.intOrNull("last_line")!!, totalLines = response.intOrNull("total_lines")!!,
            nextCursor = response.objectOrNull("next_cursor")?.let { SkillResourceCursor(it.intOrNull("line")!!, it.intOrNull("column")!!) },
        )
    }
}

private class NodeClientActionHandler(private val callback: String, private val callbacks: CallbackGateway) : ClientActionHandler {
    override suspend fun handle(item: ModelItem.ProviderItem): ModelItem? = callbacks.call(callback, item.toJson()).let {
        if (it is JsonNull) null else it.jsonObject.toModelItem()
    }
}

private class NodeListener(config: JsonObject, private val callbacks: CallbackGateway) : AgentListener {
    private val callback = config.string("callback_id")
    override fun onModelEvent(event: ModelEvent) = callbacks.emit(callback, buildJsonObject { put("type", JsonPrimitive("model_event")); put("event", event.toJson()) })
    override fun onAgentEvent(event: org.koaks.framework.loop.AgentEvent) = callbacks.emit(callback, buildJsonObject { put("type", JsonPrimitive("agent_event")); put("event", event.toJson()) })
    override fun onStep(state: org.koaks.framework.loop.AgentState) = callbacks.emit(callback, buildJsonObject { put("type", JsonPrimitive("step")); put("state", state.toJson()) })
}

private class NodeHook(config: JsonObject, private val callbacks: CallbackGateway) : Hook {
    private val beforeModel = config.stringOrNull("before_model_callback_id")
    private val afterModelEvent = config.stringOrNull("after_model_event_callback_id")
    private val beforeTool = config.stringOrNull("before_tool_callback_id")
    private val afterTool = config.stringOrNull("after_tool_callback_id")
    private val beforeToolCancel = config.stringOrNull("before_tool_cancel_callback_id")
    private val afterToolCancel = config.stringOrNull("after_tool_cancel_callback_id")
    private var hookSequence = 0L

    override suspend fun onModelRequest(ctx: StepContext): ModelRequest {
        val id = beforeModel ?: return ctx.request
        val response = callbacks.call(id, ctx.toJson())
        return if (response is JsonNull) ctx.request else response.jsonObject.toModelRequest()
    }

    override fun onModelStream(ctx: StepContext, events: Flow<ModelEvent>): Flow<ModelEvent> {
        val id = afterModelEvent ?: return events
        return events.transform { event ->
            val response = callbacks.call(id, buildJsonObject { put("context", ctx.toJson()); put("event", event.toJson()) })
            when (response) {
                JsonNull -> Unit
                is JsonObject -> when (response.stringOrNull("action")) {
                    "keep" -> emit(event)
                    "drop" -> Unit
                    "replace" -> when (val replacements = response["events"]) {
                        is JsonArray -> replacements.forEach { emit(it.jsonObject.toModelEvent()) }
                        is JsonObject -> emit(replacements.toModelEvent())
                        else -> error("replace model event decision requires 'events'")
                    }
                    null -> emit(response.toModelEvent())
                    else -> error("unknown model event decision '${response.string("action")}'")
                }
                is JsonArray -> response.forEach { emit(it.jsonObject.toModelEvent()) }
                else -> error("after model event callback must return a decision object")
            }
        }
    }

    override suspend fun onToolCall(ctx: ToolContext): ToolDecision {
        val id = beforeTool ?: return ToolDecision.Proceed
        val response = callToolHook(id, beforeToolCancel, ctx.toJson())
        if (response is JsonNull) return ToolDecision.Proceed
        val obj = response.jsonObject
        return when (obj.string("action")) {
            "proceed" -> ToolDecision.Proceed
            "deny" -> ToolDecision.Deny(obj.string("reason"))
            "replace" -> ToolDecision.ProceedWith(obj.objectOrNull("call")!!.toToolCall())
            else -> error("unknown tool decision '${obj.string("action")}'")
        }
    }

    override suspend fun onToolResult(ctx: ToolContext, outcome: ToolOutcome): ToolOutcome {
        val id = afterTool ?: return outcome
        val response = callToolHook(
            id,
            afterToolCancel,
            buildJsonObject { put("context", ctx.toJson()); put("outcome", outcome.toJson()) },
        )
        return if (response is JsonNull) outcome else response.jsonObject.toToolOutcome()
    }

    private suspend fun callToolHook(callback: String, cancelCallback: String?, payload: JsonObject): JsonElement {
        val hookExecutionId = "hook-execution-${++hookSequence}"
        val request = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("hook_execution_id", JsonPrimitive(hookExecutionId))
        }
        val execution = currentCoroutineContext()[AgentExecutionContext]
        return try {
            if (execution == null) callbacks.call(callback, request)
            else execution.waiting { callbacks.call(callback, request) }
        } catch (cancelled: CancellationException) {
            callbacks.emit(cancelCallback, buildJsonObject {
                put("hook_execution_id", JsonPrimitive(hookExecutionId))
                cancelled.message?.let { put("reason", JsonPrimitive(it)) }
            })
            throw cancelled
        }
    }
}

private fun buildTerminationPolicy(config: JsonObject): TerminationPolicy {
    val maxSteps = config.intOrNull("max_steps")
    val maxTokens = config.intOrNull("max_tokens")
    val policies = listOfNotNull(maxSteps?.let(TerminationPolicy::maxSteps), maxTokens?.let(TerminationPolicy::maxTokens))
    return when (policies.size) { 0 -> TerminationPolicy.maxSteps(Int.MAX_VALUE); 1 -> policies.single(); else -> TerminationPolicy.anyOf(*policies.toTypedArray()) }
}

private fun buildSuspendTerminationPolicy(callback: String, callbacks: CallbackGateway) = SuspendTerminationPolicy { state ->
    val decision = callbacks.call(callback, state.toJson()).jsonObject
    when (decision.string("action")) {
        "continue" -> TerminationDecision.Continue
        "stop" -> TerminationDecision.Stop(TerminationReason.Custom(decision.string("message")))
        else -> error("unknown termination decision '${decision.string("action")}'")
    }
}

private fun buildErrorPolicy(config: JsonObject): ErrorPolicy = when (config.string("type")) {
    "propagate" -> ErrorPolicy.PROPAGATE
    "retry_retriable" -> ErrorPolicy.retryRetriable(config.intOrNull("max_retries") ?: 2, config.longOrNull("delay_ms") ?: 200)
    "substitute" -> ErrorPolicy.substituteOnError(config.objectOrNull("message")!!.toModelItem())
    else -> error("unsupported error policy '${config.string("type")}'")
}

private fun buildSuspendErrorPolicy(callback: String, callbacks: CallbackGateway) = SuspendErrorPolicy { error, state ->
    val decision = callbacks.call(
        callback,
        buildJsonObject { put("error", error.toJson()); put("state", state.toJson()) },
    ).jsonObject
    when (decision.string("action")) {
        "propagate" -> Recovery.Propagate
        "retry" -> Recovery.Retry(
            delayMs = decision.longOrNull("delay_ms") ?: 0L,
            maxRetries = decision.intOrNull("max_retries") ?: 1,
        )
        "substitute" -> Recovery.Substitute(decision.objectOrNull("message")?.toModelItem() ?: error("substitute requires 'message'"))
        else -> error("unknown error policy decision '${decision.string("action")}'")
    }
}

internal fun JsonObject.toOutputSpec(): OutputSpec = OutputSpec(objectOrNull("schema") ?: error("'schema' is required"), string("name"))

private fun StepContext.toJson() = buildJsonObject {
    put("state", state.toJson()); put("request", request.toJson()); put("phase", JsonPrimitive(if (phase == ModelCallPhase.Normal) "normal" else "structured_finalization"))
}

private fun ToolContext.toJson() = buildJsonObject {
    put("call", call.toJson()); put("state", state.toJson())
    execution?.let { identity ->
        put("execution", buildJsonObject {
            put("run_id", JsonPrimitive(identity.runId)); put("agent_id", JsonPrimitive(identity.agentId))
            identity.threadId?.let { put("thread_id", JsonPrimitive(it)) }
            identity.turnId?.let { put("turn_id", JsonPrimitive(it)) }
            identity.correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
        })
    }
}

private fun ModelRequest.toJson() = buildJsonObject {
    instructions?.let { put("instructions", JsonPrimitive(it)) }; put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    put("tools", buildJsonArray { tools.forEach { schema -> add(buildJsonObject { put("name", JsonPrimitive(schema.name)); put("description", JsonPrimitive(schema.description)); put("parameters", schema.parameters) }) } })
    put("output_format", outputFormat.toJson()); checkpoint?.let { put("checkpoint", it.toJson()) }; put("idempotency_key", JsonPrimitive(idempotencyKey))
    put("event_detail", JsonPrimitive(eventDetail.name.lowercase()))
}

private fun JsonObject.toModelRequest() = ModelRequest(
    instructions = stringOrNull("instructions"), items = arrayOrNull("items").orEmpty().map { it.jsonObject.toModelItem() },
    tools = arrayOrNull("tools").orEmpty().map { obj -> obj.jsonObject.let { org.koaks.framework.tool.ToolSchema(it.string("name"), it.stringOrNull("description") ?: "", it.objectOrNull("parameters") ?: JsonObject(emptyMap())) } },
    outputFormat = objectOrNull("output_format")?.toOutputFormat() ?: OutputFormat.Text,
    checkpoint = objectOrNull("checkpoint")?.toCheckpoint(), idempotencyKey = string("idempotency_key"),
    eventDetail = when (stringOrNull("event_detail") ?: "semantic") {
        "semantic" -> EventDetail.SEMANTIC
        "lossless" -> EventDetail.LOSSLESS
        else -> error("unknown event detail '${string("event_detail")}'")
    },
)

private fun OutputFormat.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) { OutputFormat.Text -> put("type", JsonPrimitive("text")); OutputFormat.JsonObject -> put("type", JsonPrimitive("json_object")); is OutputFormat.JsonSchema -> { put("type", JsonPrimitive("json_schema")); put("name", JsonPrimitive(name)); put("schema", schema); put("strict", JsonPrimitive(strict)) } }
}

private fun JsonObject.toOutputFormat(): OutputFormat = when (string("type")) {
    "text" -> OutputFormat.Text; "json_object" -> OutputFormat.JsonObject; "json_schema" -> OutputFormat.JsonSchema(string("name"), objectOrNull("schema")!!, booleanOrNull("strict") ?: false); else -> error("unknown output format")
}

private fun ToolOutcome.toJson(): JsonObject = when (this) {
    is ToolOutcome.Success -> buildJsonObject { put("type", JsonPrimitive("success")); put("output", JsonPrimitive(output)); put("return_directly", JsonPrimitive(returnDirectly)) }
    is ToolOutcome.Failure -> buildJsonObject { put("type", JsonPrimitive("failure")); put("error", error.toJson()) }
}

private fun JsonObject.toToolOutcome(): ToolOutcome = when (string("type")) {
    "success" -> ToolOutcome.Success(string("output"), booleanOrNull("return_directly") ?: false)
    "failure" -> ToolOutcome.Failure(AgentError.ToolError("hook", objectOrNull("error")?.stringOrNull("message") ?: "hook failure", false))
    else -> error("unknown tool outcome")
}
