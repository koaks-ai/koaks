@file:OptIn(ExperimentalJsExport::class)

package org.koaks.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.EventDetail
import org.koaks.framework.tool.ToolOutputStream
import org.koaks.framework.tool.ToolProgress
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.fault.CircuitBreakerPolicy
import org.koaks.runtime.fault.SupervisedHandle
import org.koaks.runtime.fault.SupervisionPolicy
import org.koaks.runtime.ipc.RuntimeMessage
import org.koaks.runtime.resource.AccessMode
import org.koaks.runtime.resource.ChildConversation
import org.koaks.runtime.resource.ChildFailurePolicy
import org.koaks.runtime.resource.Quota
import org.koaks.runtime.resource.withRuntimeResource
import org.koaks.runtime.sched.taskGraph
import kotlin.js.Promise

private data class AgentRecord(val key: String, val built: BuiltAgent)
private data class HandleRecord(val agentKey: String, val handle: AgentHandle, val parentRunId: RunId? = null)
private data class SupervisedRecord(val agentKey: String, val handle: SupervisedHandle)
private data class SubscriptionRecord(val agentKey: String?, val job: Job)
private data class OperationRecord(val agentKey: String?, val job: Job)

@JsExport
class KoaksBridge internal constructor(
    configJson: String,
    invoke: (String, String) -> Promise<String>,
    notify: (String, String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val callbacks = CallbackGateway(invoke, notify)
    private val toolExecutions = ToolExecutionRegistry()
    private val runtimeOwned = mutableListOf<AutoCloseable>()
    private val runtime: AgentRuntime
    private val agents = mutableMapOf<String, AgentRecord>()
    private val agentKeysById = mutableMapOf<AgentId, String>()
    private val handles = mutableMapOf<String, HandleRecord>()
    private val supervised = mutableMapOf<String, SupervisedRecord>()
    private val subscriptions = mutableMapOf<String, SubscriptionRecord>()
    private val operations = mutableMapOf<String, OperationRecord>()
    private var sequence = 0L
    private var closed = false

    init {
        val config = parseObject(configJson)
        runtime = AgentRuntime {
            maxConcurrency = config.intOrNull("max_concurrency") ?: Int.MAX_VALUE
            runEventBufferCapacity = config.intOrNull("run_event_buffer_capacity") ?: 1024
            defaultQuota = config.objectOrNull("default_quota")?.toQuota() ?: Quota.UNLIMITED
            config.objectOrNull("default_memory")?.let {
                defaultMemoryProvider = buildMemory(it, callbacks, runtimeOwned)
            }
        }
    }

    fun request(method: String, paramsJson: String): Promise<String> = scope.promise {
        try {
            success(dispatch(method, parseObject(paramsJson)))
        } catch (failure: Throwable) {
            failure(failure)
        }
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement {
        if (closed && method != "runtime.close") error("Koaks runtime is closed")
        return when (method) {
            "runtime.create_agent" -> createAgent(params, replace = false)
            "runtime.replace_agent" -> createAgent(params, replace = true)
            "runtime.metrics" -> runtime.metrics().toJson()
            "runtime.runs" -> buildJsonArray { runtime.runs.forEach { add(it.toJson()) } }
            "runtime.snapshot" -> runtime.snapshot(params.string("run_id").toRunId())?.toJson() ?: JsonNull
            "runtime.thread_snapshot" -> runtime.threadSnapshot(ThreadId(params.string("thread_id")))?.toJson() ?: JsonNull
            "runtime.reap" -> reap(params)
            "runtime.put_context" -> putContext(params)
            "runtime.delta_context" -> deltaContext(params)
            "runtime.resolve_context" -> resolveContext(params)
            "runtime.submit" -> submit(params)
            "runtime.spawn_supervised" -> spawnSupervised(params)
            "runtime.ipc.send" -> runtimeIpcSend(params)
            "runtime.ipc.request" -> runtimeIpcRequest(params)
            "runtime.ipc.publish" -> runtimeIpcPublish(params)
            "runtime.ipc.subscribe" -> runtimeIpcSubscribe(params)
            "runtime.events" -> startSubscription(
                agentKey = null,
                callbackId = params.string("callback_id"),
                events = runtime.events.map { it.toJson() },
            )
            "runtime.close" -> {
                closeRuntime()
                JsonNull
            }

            "agent.prepare" -> {
                agent(params).prepare()
                JsonNull
            }
            "agent.run" -> runAgent(params, structured = false)
            "agent.run_structured" -> runAgent(params, structured = true)
            "agent.stream" -> streamAgent(params, resume = false)
            "agent.resume" -> streamAgent(params, resume = true)
            "agent.resume_run" -> resumeAgent(params)
            "agent.spawn" -> spawnAgent(params)
            "agent.spawn_structured" -> spawnAgent(params, structured = true)
            "agent.spawn_resume" -> spawnResumeAgent(params)
            "agent.close" -> {
                closeAgent(params.string("agent_key"), unregister = true)
                JsonNull
            }

            "handle.result" -> handleResult(params)
            "handle.cancel" -> {
                handle(params).handle.cancel(params.stringOrNull("reason"))
                JsonNull
            }
            "handle.pause" -> {
                handle(params).handle.pause()
                JsonNull
            }
            "handle.resume" -> {
                handle(params).handle.resume()
                JsonNull
            }
            "handle.snapshot" -> handle(params).handle.snapshot.toJson()
            "handle.updates" -> {
                val record = handle(params)
                startSubscription(
                    record.agentKey,
                    params.string("callback_id"),
                    record.handle.updates.map { it.toJson() },
                    params.stringOrNull("execution_id"),
                )
            }
            "handle.events" -> {
                val record = handle(params)
                startSubscription(
                    record.agentKey,
                    params.string("callback_id"),
                    record.handle.events(params.longOrNull("after_sequence")).map { it.toJson() },
                    params.stringOrNull("execution_id"),
                )
            }
            "handle.release" -> {
                handles.remove(params.string("handle_id"))
                JsonNull
            }

            "supervised.result" -> supervised(params).handle.await().toJson()
            "supervised.cancel" -> {
                supervised(params).handle.cancel(params.stringOrNull("reason"))
                JsonNull
            }
            "supervised.release" -> {
                supervised.remove(params.string("supervised_id"))
                JsonNull
            }

            "tool.resource.with" -> toolWithResource(params)
            "tool.progress" -> toolProgress(params)
            "tool.context.put" -> toolPutContext(params)
            "tool.context.delta" -> toolDeltaContext(params)
            "tool.context.resolve" -> toolResolveContext(params)
            "tool.spawn_child" -> toolSpawnChild(params)
            "tool.ipc.send" -> toolIpcSend(params)
            "tool.ipc.receive" -> toolIpcReceive(params)
            "tool.ipc.request" -> toolIpcRequest(params)
            "tool.ipc.reply" -> toolIpcReply(params)
            "tool.ipc.publish" -> toolIpcPublish(params)
            "tool.ipc.subscribe" -> toolIpcSubscribe(params)

            "subscription.cancel" -> {
                subscriptions.remove(params.string("subscription_id"))?.job?.cancelAndJoin()
                JsonNull
            }
            "operation.cancel" -> {
                operations[params.string("operation_id")]?.job?.cancel(CancellationException(params.stringOrNull("reason") ?: "operation cancelled"))
                JsonNull
            }
            else -> error("unknown bridge method '$method'")
        }
    }

    private suspend fun createAgent(params: JsonObject, replace: Boolean): JsonObject {
        val config = params.objectOrNull("config") ?: error("'config' is required")
        val publicId = config.string("id")
        val existingKey = agentKeysById[AgentId(publicId)]
        if (!replace) require(existingKey == null) { "agent '$publicId' already exists" }
        if (replace) require(existingKey != null) { "agent '$publicId' does not exist" }

        val built = try {
            buildAgent(config, callbacks, toolExecutions)
        } catch (failure: Throwable) {
            throw IllegalArgumentException("Invalid agent config: ${failure.message}", failure)
        }
        return try {
            if (replace) runtime.replaceAgent(built.agent)
            val key = nextId("agent")
            val record = AgentRecord(key, built)
            agents[key] = record
            agentKeysById[built.agent.id] = key
            if (existingKey != null) closeAgent(existingKey, unregister = false)
            agentDescriptor(record)
        } catch (failure: Throwable) {
            closeBuiltAgent(built, deferOwned = false)
            throw failure
        }
    }

    private suspend fun runAgent(params: JsonObject, structured: Boolean): JsonObject {
        val record = agentRecord(params)
        return withOperation(params.stringOrNull("operation_id"), record.key) {
            val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
            val result = if (structured) {
                runtime.runStructured(
                    record.built.agent,
                    params.string("input"),
                    params.objectOrNull("output")?.toOutputSpec() ?: error("'output' is required"),
                    priority = options.intOrNull("priority") ?: 0,
                    quota = options.objectOrNull("quota")?.toQuota(),
                    contextRefs = options.contextRefs(),
                    thread = options.stringOrNull("thread_id")?.let(::ThreadId),
                    correlationId = options.stringOrNull("correlation_id"),
                    eventDetail = options.eventDetail(),
                )
            } else {
                runtime.run(
                    record.built.agent,
                    params.string("input"),
                    priority = options.intOrNull("priority") ?: 0,
                    quota = options.objectOrNull("quota")?.toQuota(),
                    contextRefs = options.contextRefs(),
                    thread = options.stringOrNull("thread_id")?.let(::ThreadId),
                    correlationId = options.stringOrNull("correlation_id"),
                    eventDetail = options.eventDetail(),
                )
            }
            result.toJson()
        }
    }

    private fun streamAgent(params: JsonObject, resume: Boolean): JsonPrimitive {
        val record = agentRecord(params)
        val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
        val flow = if (resume) {
            runtime.resume(
                record.built.agent,
                ThreadId(params.string("thread_id")),
                priority = options.intOrNull("priority") ?: 0,
                quota = options.objectOrNull("quota")?.toQuota(),
                contextRefs = options.contextRefs(),
                correlationId = options.stringOrNull("correlation_id"),
                eventDetail = options.eventDetail(),
            )
        } else {
            runtime.stream(
                record.built.agent,
                params.string("input"),
                priority = options.intOrNull("priority") ?: 0,
                quota = options.objectOrNull("quota")?.toQuota(),
                contextRefs = options.contextRefs(),
                thread = options.stringOrNull("thread_id")?.let(::ThreadId),
                correlationId = options.stringOrNull("correlation_id"),
                eventDetail = options.eventDetail(),
            )
        }
        return startSubscription(record.key, params.string("callback_id"), flow.map { it.toJson() })
    }

    private suspend fun resumeAgent(params: JsonObject): JsonObject {
        val record = agentRecord(params)
        val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
        return withOperation(params.stringOrNull("operation_id"), record.key) {
            runtime.resumeRun(
                record.built.agent,
                ThreadId(params.string("thread_id")),
                priority = options.intOrNull("priority") ?: 0,
                quota = options.objectOrNull("quota")?.toQuota(),
                contextRefs = options.contextRefs(),
                correlationId = options.stringOrNull("correlation_id"),
                eventDetail = options.eventDetail(),
            ).toJson()
        }
    }

    private fun spawnAgent(params: JsonObject, structured: Boolean = false): JsonObject {
        val record = agentRecord(params)
        val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
        val handle = if (structured) {
            runtime.spawnStructured(
                record.built.agent,
                params.string("input"),
                params.objectOrNull("output")?.toOutputSpec() ?: error("'output' is required"),
                priority = options.intOrNull("priority") ?: 0,
                quota = options.objectOrNull("quota")?.toQuota(),
                contextRefs = options.contextRefs(),
                thread = options.stringOrNull("thread_id")?.let(::ThreadId),
                correlationId = options.stringOrNull("correlation_id"),
                eventDetail = options.eventDetail(),
            )
        } else {
            runtime.spawn(
                record.built.agent,
                params.string("input"),
                priority = options.intOrNull("priority") ?: 0,
                quota = options.objectOrNull("quota")?.toQuota(),
                contextRefs = options.contextRefs(),
                thread = options.stringOrNull("thread_id")?.let(::ThreadId),
                correlationId = options.stringOrNull("correlation_id"),
                eventDetail = options.eventDetail(),
            )
        }
        val handleId = nextId("handle")
        handles[handleId] = HandleRecord(record.key, handle)
        return handleDescriptor(handleId, handle, parentRunId = null)
    }

    private fun spawnResumeAgent(params: JsonObject): JsonObject {
        val record = agentRecord(params)
        val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
        val handle = runtime.spawnResume(
            record.built.agent,
            ThreadId(params.string("thread_id")),
            priority = options.intOrNull("priority") ?: 0,
            quota = options.objectOrNull("quota")?.toQuota(),
            contextRefs = options.contextRefs(),
            correlationId = options.stringOrNull("correlation_id"),
            eventDetail = options.eventDetail(),
        )
        val handleId = nextId("handle")
        handles[handleId] = HandleRecord(record.key, handle)
        return handleDescriptor(handleId, handle, parentRunId = null)
    }

    private suspend fun submit(params: JsonObject): JsonObject {
        val graph = taskGraph {
            params.arrayOrNull("tasks").orEmpty().forEach { element ->
                val task = element.jsonObject
                val record = agents[task.string("agent_key")] ?: error("unknown agent")
                val inputCallback = task.stringOrNull("input_callback_id")
                if (inputCallback == null) {
                    task(
                        id = task.string("id"),
                        agent = record.built.agent,
                        input = task.string("input"),
                        priority = task.intOrNull("priority") ?: 0,
                        dependsOn = task.arrayOrNull("depends_on").orEmpty().map { it.jsonPrimitive.content },
                    )
                } else {
                    task(
                        id = task.string("id"),
                        agent = record.built.agent,
                        priority = task.intOrNull("priority") ?: 0,
                        dependsOn = task.arrayOrNull("depends_on").orEmpty().map { it.jsonPrimitive.content },
                    ) { deps ->
                        callbacks.call(
                            inputCallback,
                            buildJsonObject {
                                put("dependencies", buildJsonObject { deps.forEach { (id, result) -> put(id, result.toJson()) } })
                            },
                        ).jsonPrimitive.content
                    }
                }
            }
        }
        return buildJsonObject { runtime.submit(graph).forEach { (id, result) -> put(id, result.toJson()) } }
    }

    private fun spawnSupervised(params: JsonObject): JsonObject {
        val record = agentRecord(params)
        val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
        val policyConfig = params.objectOrNull("policy") ?: JsonObject(emptyMap())
        val retryMode = policyConfig.stringOrNull("retry_on") ?: "failed"
        val recoverCallback = policyConfig.stringOrNull("recover_callback_id")
        val policy = SupervisionPolicy(
            maxRetries = policyConfig.intOrNull("max_retries") ?: 2,
            initialBackoffMillis = policyConfig.longOrNull("initial_backoff_ms") ?: 100L,
            backoffFactor = policyConfig.doubleOrNull("backoff_factor") ?: 2.0,
            maxBackoffMillis = policyConfig.longOrNull("max_backoff_ms") ?: 5_000L,
            circuitBreaker = policyConfig.objectOrNull("circuit_breaker")?.let {
                CircuitBreakerPolicy(
                    failureThreshold = it.intOrNull("failure_threshold") ?: 5,
                    resetTimeoutMillis = it.longOrNull("reset_timeout_ms") ?: 30_000L,
                )
            },
            retryOn = { result ->
                when (retryMode) {
                    "failed" -> result is org.koaks.framework.loop.AgentResult.Failed
                    "not_completed" -> result !is org.koaks.framework.loop.AgentResult.Completed
                    else -> error("unknown supervision retry_on '$retryMode'")
                }
            },
            recover = recoverCallback?.let { callback ->
                { attempt, last ->
                    callbacks.call(
                        callback,
                        buildJsonObject { put("attempt", JsonPrimitive(attempt)); put("last", last.toJson()) },
                    ).jsonPrimitive.content
                }
            },
        )
        val handle = runtime.spawnSupervised(
            record.built.agent,
            params.string("input"),
            policy,
            priority = options.intOrNull("priority") ?: 0,
            quota = options.objectOrNull("quota")?.toQuota(),
            contextRefs = options.contextRefs(),
            thread = options.stringOrNull("thread_id")?.let(::ThreadId),
        )
        val id = nextId("supervised")
        supervised[id] = SupervisedRecord(record.key, handle)
        return buildJsonObject { put("supervised_id", JsonPrimitive(id)) }
    }

    private fun putContext(params: JsonObject): JsonPrimitive {
        val (contextScope, owner) = params.contextScope()
        val ref = runtime.context.put(params.items(), contextScope, owner)
        return JsonPrimitive(ref.id)
    }

    private fun deltaContext(params: JsonObject): JsonPrimitive {
        val (contextScope, owner) = params.contextScope()
        val ref = runtime.context.delta(ContextRef(params.string("parent_ref")), params.items(), contextScope, owner)
        return JsonPrimitive(ref.id)
    }

    private fun resolveContext(params: JsonObject): JsonArray = buildJsonArray {
        runtime.context.resolve(
            ContextRef(params.string("ref")),
            params.stringOrNull("requester_run_id")?.toRunId(),
        ).forEach { add(it.toJson()) }
    }

    private fun reap(params: JsonObject): JsonPrimitive {
        val count = runtime.reap(params.longOrNull("older_than_ms") ?: 0L)
        handles.entries.removeAll { runtime.snapshot(it.value.handle.runId) == null }
        return JsonPrimitive(count)
    }

    private suspend fun handleResult(params: JsonObject): JsonObject {
        val record = handle(params)
        val executionId = params.stringOrNull("execution_id")
        return if (executionId == null) {
            record.handle.await().toJson()
        } else {
            toolExecutions.execute(executionId) { record.handle.await().toJson() }
        }
    }

    private suspend fun toolWithResource(params: JsonObject): JsonElement =
        toolExecutions.execute(params.string("execution_id")) {
            val mode = when (params.stringOrNull("mode") ?: "write") {
                "read" -> AccessMode.READ
                "write" -> AccessMode.WRITE
                else -> throw IllegalArgumentException("unknown resource access mode '${params.string("mode")}'")
            }
            withRuntimeResource(params.string("resource_id"), mode) {
                val executionContext = currentCoroutineContext()[AgentExecutionContext]
                    ?: throw NodeBridgeException("lifecycle_error", "Resource operation has no execution branch")
                executionContext.waiting {
                    callbacks.call(params.string("callback_id"))
                }
            }
            JsonNull
        }

    private suspend fun toolPutContext(params: JsonObject): JsonPrimitive =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val scope = params.toolContextScope()
            val owner = execution.runtimeContext.runId.takeIf { scope == ContextScope.PRIVATE }
            JsonPrimitive(execution.runtimeContext.context.put(params.items(), scope, owner).id)
        }

    private suspend fun toolDeltaContext(params: JsonObject): JsonPrimitive =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val scope = params.toolContextScope()
            val owner = execution.runtimeContext.runId.takeIf { scope == ContextScope.PRIVATE }
            JsonPrimitive(
                execution.runtimeContext.context.delta(
                    ContextRef(params.string("parent_ref")),
                    params.items(),
                    scope,
                    owner,
                ).id,
            )
        }

    private suspend fun toolResolveContext(params: JsonObject): JsonArray =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            buildJsonArray {
                execution.runtimeContext.context.resolve(
                    ContextRef(params.string("ref")),
                    execution.runtimeContext.runId,
                ).forEach { add(it.toJson()) }
            }
        }

    private suspend fun toolSpawnChild(params: JsonObject): JsonObject =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val child = agents[params.string("agent_key")]
                ?: throw NodeBridgeException("cross_runtime_agent", "spawnChild requires an Agent from the same KoaksRuntime")
            val options = params.objectOrNull("options") ?: JsonObject(emptyMap())
            val conversation = when (val configured = options.objectOrNull("conversation")) {
                null -> ChildConversation.Inherit
                else -> when (configured.stringOrNull("type") ?: "inherit") {
                    "inherit" -> ChildConversation.Inherit
                    "ephemeral" -> ChildConversation.Ephemeral
                    "thread" -> ChildConversation.Thread(ThreadId(configured.string("thread_id")))
                    else -> throw IllegalArgumentException("unknown child conversation '${configured.string("type")}'")
                }
            }
            val failurePolicy = when (options.stringOrNull("failure_policy") ?: "propagate") {
                "propagate" -> ChildFailurePolicy.PROPAGATE
                "capture" -> ChildFailurePolicy.CAPTURE
                else -> throw IllegalArgumentException("unknown child failure policy '${options.string("failure_policy")}'")
            }
            val handle = execution.runtimeContext.spawn(
                child.built.agent,
                params.string("input"),
                priority = options.intOrNull("priority") ?: 0,
                quota = options.objectOrNull("quota")?.toQuota(),
                contextRefs = options.contextRefs(),
                failurePolicy = failurePolicy,
                conversation = conversation,
            )
            val handleId = nextId("handle")
            handles[handleId] = HandleRecord(child.key, handle, execution.runtimeContext.runId)
            handleDescriptor(handleId, handle, execution.runtimeContext.runId)
        }

    private suspend fun toolIpcSend(params: JsonObject): JsonElement =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val target = requireIpcTarget(params.string("to_run_id"))
            execution.runtimeContext.ipc.send(
                params.toRuntimeMessage(execution.runtimeContext.ipc.nextId(), execution.runtimeContext.runId, target),
            )
            JsonNull
        }

    private suspend fun toolIpcReceive(params: JsonObject): JsonObject =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val message = execution.executionContext.waiting {
                execution.runtimeContext.ipc.mailbox(execution.runtimeContext.runId).receive()
            }
            val token = message.correlationId?.let { execution.issueReplyToken(message) }
            message.toJson(token)
        }

    private suspend fun toolIpcRequest(params: JsonObject): JsonObject =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val target = requireIpcTarget(params.string("to_run_id"))
            val message = params.toRuntimeMessage(
                execution.runtimeContext.ipc.nextId(),
                execution.runtimeContext.runId,
                target,
            )
            execution.executionContext.waiting {
                withIpcTimeout(params) { execution.runtimeContext.ipc.request(message) }
            }.toJson()
        }

    private suspend fun toolIpcReply(params: JsonObject): JsonElement =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val request = execution.takeReplyToken(params.string("reply_token"))
            if (request.receiver != execution.runtimeContext.runId) {
                throw NodeBridgeException("ipc_reply_invalid", "IPC request does not belong to the current run")
            }
            execution.runtimeContext.ipc.reply(request, params.stringOrNull("payload") ?: "", params.contextRefs())
            JsonNull
        }

    private suspend fun toolIpcPublish(params: JsonObject): JsonElement =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            execution.runtimeContext.ipc.publish(
                params.string("topic"),
                params.toRuntimeMessage(
                    execution.runtimeContext.ipc.nextId(),
                    execution.runtimeContext.runId,
                    receiver = null,
                ),
            )
            JsonNull
        }

    private suspend fun toolIpcSubscribe(params: JsonObject): JsonPrimitive =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            startSubscription(
                agentKey = agentKeysById[execution.runtimeContext.agentId],
                callbackId = params.string("callback_id"),
                events = execution.runtimeContext.ipc.subscribe(params.string("topic")).map { it.toJson() },
                toolExecutionId = execution.id,
            )
        }

    private suspend fun toolProgress(params: JsonObject): JsonElement =
        toolExecutions.execute(params.string("execution_id")) { execution ->
            val invocation = execution.invocationContext
                ?: throw NodeBridgeException("lifecycle_error", "Tool progress requires a contextual tool invocation")
            invocation.reportProgress(params.objectOrNull("progress")?.toToolProgress() ?: error("'progress' is required"))
            JsonNull
        }

    private suspend fun runtimeIpcSend(params: JsonObject): JsonElement {
        val target = requireIpcTarget(params.string("to_run_id"))
        runtime.ipc.send(params.toRuntimeMessage(runtime.ipc.nextId(), sender = null, receiver = target))
        return JsonNull
    }

    private suspend fun runtimeIpcRequest(params: JsonObject): JsonObject =
        withOperation(params.stringOrNull("operation_id"), agentKey = null) {
            val target = requireIpcTarget(params.string("to_run_id"))
            withIpcTimeout(params) {
                runtime.ipc.request(params.toRuntimeMessage(runtime.ipc.nextId(), sender = null, receiver = target))
            }.toJson()
        }

    private suspend fun runtimeIpcPublish(params: JsonObject): JsonElement {
        runtime.ipc.publish(
            params.string("topic"),
            params.toRuntimeMessage(runtime.ipc.nextId(), sender = null, receiver = null),
        )
        return JsonNull
    }

    private fun runtimeIpcSubscribe(params: JsonObject): JsonPrimitive =
        startSubscription(
            agentKey = null,
            callbackId = params.string("callback_id"),
            events = runtime.ipc.subscribe(params.string("topic")).map { it.toJson() },
        )

    private fun requireIpcTarget(value: String): RunId {
        val runId = value.toRunId()
        val snapshot = runtime.snapshot(runId)
        if (snapshot == null || snapshot.state.isTerminal) {
            throw NodeBridgeException("ipc_target_unavailable", "IPC target run '$value' is unavailable")
        }
        return runId
    }

    private suspend fun <T> withIpcTimeout(params: JsonObject, block: suspend () -> T): T {
        val timeout = params.longOrNull("timeout_ms") ?: return block()
        require(timeout > 0L) { "timeoutMs must be greater than zero" }
        return try {
            withTimeout(timeout) { block() }
        } catch (_: TimeoutCancellationException) {
            throw NodeBridgeException("timeout", "IPC request timed out after ${timeout}ms")
        }
    }

    private fun startSubscription(
        agentKey: String?,
        callbackId: String,
        events: Flow<JsonObject>,
        toolExecutionId: String? = null,
    ): JsonPrimitive {
        val id = nextId("subscription")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                events.collect { event ->
                    callbacks.call(callbackId, buildJsonObject { put("type", JsonPrimitive("next")); put("value", event) })
                }
                callbacks.call(callbackId, buildJsonObject { put("type", JsonPrimitive("complete")) })
            } catch (_: CancellationException) {
                runCatching { callbacks.call(callbackId, buildJsonObject { put("type", JsonPrimitive("complete")) }) }
            } catch (failure: Throwable) {
                runCatching {
                    callbacks.call(
                        callbackId,
                        buildJsonObject { put("type", JsonPrimitive("error")); put("error", errorJson(failure)) },
                    )
                }
            } finally {
                subscriptions.remove(id)
            }
        }
        subscriptions[id] = SubscriptionRecord(agentKey, job)
        if (toolExecutionId != null) {
            try {
                toolExecutions.track(toolExecutionId, job)
            } catch (failure: Throwable) {
                subscriptions.remove(id)
                job.cancel()
                throw failure
            }
        }
        job.start()
        return JsonPrimitive(id)
    }

    private suspend fun <T : JsonElement> withOperation(
        operationId: String?,
        agentKey: String?,
        block: suspend () -> T,
    ): T {
        if (operationId == null) return block()
        val job = currentCoroutineContext()[Job] ?: error("operation has no coroutine job")
        require(operations.put(operationId, OperationRecord(agentKey, job)) == null) {
            "operation '$operationId' already exists"
        }
        return try {
            block()
        } finally {
            operations.remove(operationId)
        }
    }

    private suspend fun closeAgent(key: String, unregister: Boolean) {
        val record = agents[key] ?: return
        toolExecutions.closeAgent(record.built.agent.id)
        val agentSubscriptions = subscriptions.values.filter { it.agentKey == key }.map { it.job }
        val agentOperations = operations.values.filter { it.agentKey == key }.map { it.job }
        val agentHandles = handles.values.filter { it.agentKey == key }.map { it.handle }
        val agentSupervised = supervised.values.filter { it.agentKey == key }.map { it.handle }
        agentSubscriptions.forEach { it.cancel() }
        agentOperations.forEach { it.cancel() }
        agentHandles.forEach { it.cancel("agent closed") }
        agentSupervised.forEach { it.cancel("agent closed") }

        agentSubscriptions.joinAll()
        agentOperations.joinAll()
        agentHandles.forEach { runCatching { it.join() } }
        agentSupervised.forEach { runCatching { it.await() } }

        subscriptions.entries.removeAll { it.value.agentKey == key }
        operations.entries.removeAll { it.value.agentKey == key }
        handles.entries.removeAll { it.value.agentKey == key }
        supervised.entries.removeAll { it.value.agentKey == key }
        if (unregister) runtime.unregister(record.built.agent.id)
        agents.remove(key)
        if (agentKeysById[record.built.agent.id] == key) agentKeysById.remove(record.built.agent.id)
        closeBuiltAgent(record.built, deferOwned = true)
    }

    private suspend fun closeRuntime() {
        if (closed) return
        closed = true
        toolExecutions.closeAll()
        val activeSubscriptions = subscriptions.values.map { it.job }
        val activeOperations = operations.values.map { it.job }
        val activeHandles = handles.values.map { it.handle }
        val activeSupervised = supervised.values.map { it.handle }
        activeSubscriptions.forEach { it.cancel() }
        activeOperations.forEach { it.cancel() }
        activeHandles.forEach { it.cancel("runtime closed") }
        activeSupervised.forEach { it.cancel("runtime closed") }
        activeSubscriptions.joinAll()
        activeOperations.joinAll()
        activeHandles.forEach { runCatching { it.join() } }
        activeSupervised.forEach { runCatching { it.await() } }
        agents.values.toList().forEach { closeBuiltAgent(it.built, deferOwned = false) }
        agents.clear()
        agentKeysById.clear()
        handles.clear()
        supervised.clear()
        subscriptions.clear()
        operations.clear()
        runtime.close()
        runtimeOwned.asReversed().forEach { runCatching { it.close() } }
        runtimeOwned.clear()
    }

    private fun closeBuiltAgent(built: BuiltAgent, deferOwned: Boolean) {
        runCatching { built.agent.close() }
        if (deferOwned) {
            runtimeOwned += built.owned
        } else {
            built.owned.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun agent(params: JsonObject): Agent = agentRecord(params).built.agent

    private fun agentRecord(params: JsonObject): AgentRecord =
        agents[params.string("agent_key")] ?: error("unknown or closed agent")

    private fun handle(params: JsonObject): HandleRecord =
        handles[params.string("handle_id")] ?: error("unknown or released run handle")

    private fun supervised(params: JsonObject): SupervisedRecord =
        supervised[params.string("supervised_id")] ?: error("unknown or released supervised handle")

    private fun nextId(prefix: String): String = "$prefix-${++sequence}"
}

@JsExport
fun createKoaksBridge(
    configJson: String,
    invoke: (String, String) -> Promise<String>,
    notify: (String, String) -> Unit,
): KoaksBridge = KoaksBridge(configJson, invoke, notify)

private fun JsonObject.toQuota(): Quota = Quota(
    maxSteps = intOrNull("max_steps"),
    maxToolCalls = intOrNull("max_tool_calls"),
    wallClockMillis = longOrNull("wall_clock_ms"),
)

private fun JsonObject.toToolProgress(): ToolProgress = when (string("type")) {
    "output" -> ToolProgress.Output(
        text = string("text"),
        stream = if (stringOrNull("stream") == "stderr") ToolOutputStream.Stderr else ToolOutputStream.Stdout,
    )
    "status" -> ToolProgress.Status(string("message"))
    "custom" -> ToolProgress.Custom(string("kind"), get("payload") ?: JsonNull)
    else -> throw IllegalArgumentException("unknown tool progress type '${string("type")}'")
}

private fun JsonObject.contextRefs(): List<ContextRef> =
    arrayOrNull("context_refs").orEmpty().map { ContextRef(it.jsonPrimitive.content) }

private fun JsonObject.eventDetail(): EventDetail = when (stringOrNull("event_detail") ?: "semantic") {
    "semantic" -> EventDetail.SEMANTIC
    "lossless" -> EventDetail.LOSSLESS
    else -> throw IllegalArgumentException("eventDetail must be 'semantic' or 'lossless'")
}

private fun JsonObject.items() = arrayOrNull("items").orEmpty().map { it.jsonObject.toModelItem() }

private fun JsonObject.contextScope(): Pair<ContextScope, RunId?> {
    val scope = objectOrNull("scope") ?: return ContextScope.GLOBAL to null
    return when (scope.stringOrNull("type") ?: "global") {
        "global" -> ContextScope.GLOBAL to null
        "task" -> ContextScope.TASK to null
        "private" -> ContextScope.PRIVATE to scope.string("owner_run_id").toRunId()
        else -> error("unknown context scope '${scope.string("type")}'")
    }
}

private fun String.toRunId(): RunId = RunId(toLong())

private fun agentDescriptor(record: AgentRecord): JsonObject = buildJsonObject {
    put("agent_key", JsonPrimitive(record.key))
    put("id", JsonPrimitive(record.built.agent.id.value))
    put("name", JsonPrimitive(record.built.agent.name))
}

private fun handleDescriptor(id: String, handle: AgentHandle, parentRunId: RunId?): JsonObject = buildJsonObject {
    put("handle_id", JsonPrimitive(id))
    put("run_id", JsonPrimitive(handle.runId.value.toString()))
    put("agent_id", JsonPrimitive(handle.agentId.value))
    handle.threadId?.let { put("thread_id", JsonPrimitive(it.value)) }
    handle.turnId?.let { put("turn_id", JsonPrimitive(it.value.toString())) }
    handle.correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
    parentRunId?.let { put("parent_run_id", JsonPrimitive(it.value.toString())) }
}

private fun JsonObject.toolContextScope(): ContextScope = when (stringOrNull("scope") ?: "private") {
    "private" -> ContextScope.PRIVATE
    "task" -> ContextScope.TASK
    "global" -> ContextScope.GLOBAL
    else -> throw IllegalArgumentException("unknown Tool Context scope '${string("scope")}'")
}

private fun JsonObject.toRuntimeMessage(id: Long, sender: RunId?, receiver: RunId?): RuntimeMessage = RuntimeMessage(
    id = id,
    sender = sender,
    receiver = receiver,
    type = string("type"),
    payload = stringOrNull("payload") ?: "",
    contextRefs = contextRefs(),
    priority = intOrNull("priority") ?: 0,
    deadlineMillis = longOrNull("deadline_epoch_ms"),
)

private fun RuntimeMessage.toJson(replyToken: String? = null): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id.toString()))
    sender?.let { put("sender_run_id", JsonPrimitive(it.value.toString())) }
    receiver?.let { put("receiver_run_id", JsonPrimitive(it.value.toString())) }
    put("type", JsonPrimitive(type))
    put("payload", JsonPrimitive(payload))
    put("context_refs", buildJsonArray { contextRefs.forEach { add(JsonPrimitive(it.id)) } })
    put("priority", JsonPrimitive(priority))
    deadlineMillis?.let { put("deadline_epoch_ms", JsonPrimitive(it)) }
    replyToken?.let { put("reply_token", JsonPrimitive(it)) }
}

private fun errorJson(error: Throwable): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(error.bridgeErrorCode()))
    put("message", JsonPrimitive(error.message ?: error::class.simpleName ?: "unknown error"))
    val stack = (error as? NodeCallbackException)?.callbackStack
    stack?.takeIf { it.isNotBlank() }?.let { put("stack", JsonPrimitive(it)) }
}
