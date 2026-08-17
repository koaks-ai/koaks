package org.koaks.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.AgentState
import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.InterruptReason
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.memory.PendingWork
import org.koaks.framework.memory.TurnStatus
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.Role
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.RunEventEnvelope
import org.koaks.runtime.acb.RunEventPayload
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.observe.RuntimeMetrics
import org.koaks.framework.tool.ToolOutputStream
import org.koaks.framework.tool.ToolProgress
import org.koaks.runtime.thread.ThreadSnapshot

internal val nodeJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}

internal fun parseObject(text: String): JsonObject =
    if (text.isBlank()) JsonObject(emptyMap()) else nodeJson.parseToJsonElement(text).jsonObject

internal fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("'$name' is required")

internal fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.intOrNull(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
internal fun JsonObject.longOrNull(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
internal fun JsonObject.doubleOrNull(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
internal fun JsonObject.booleanOrNull(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
internal fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject
internal fun JsonObject.arrayOrNull(name: String): JsonArray? = this[name] as? JsonArray

internal fun success(value: JsonElement = JsonNull): String = nodeJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("value", value)
    },
)

internal fun failure(error: Throwable): String = nodeJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("ok", JsonPrimitive(false))
        put(
            "error",
            buildJsonObject {
                put("type", JsonPrimitive(error.bridgeErrorCode()))
                put("message", JsonPrimitive(error.message ?: error::class.simpleName ?: "unknown error"))
                val stack = (error as? NodeCallbackException)?.callbackStack ?: error.stackTraceToString()
                stack.takeIf { it.isNotBlank() }?.let { put("stack", JsonPrimitive(it)) }
            },
        )
    },
)

internal fun Throwable.bridgeErrorCode(): String = when (this) {
    is kotlinx.coroutines.CancellationException -> "cancelled"
    is NodeBridgeException -> code
    is NodeCallbackException -> callbackType
    is IllegalArgumentException -> "configuration_error"
    is org.koaks.runtime.AgentIdConflictException -> "agent_conflict"
    is org.koaks.runtime.context.ContextAccessException -> "context_access"
    is IllegalStateException -> "lifecycle_error"
    else -> "bridge_error"
}

internal class NodeCallbackException(
    val callbackType: String,
    message: String,
    val callbackStack: String?,
) : RuntimeException(message)

internal fun Usage.toJson(): JsonObject = buildJsonObject {
    put("prompt_tokens", JsonPrimitive(promptTokens))
    put("completion_tokens", JsonPrimitive(completionTokens))
    put("total_tokens", JsonPrimitive(totalTokens))
    put("cached_input_tokens", JsonPrimitive(cachedInputTokens))
    put("reasoning_output_tokens", JsonPrimitive(reasoningOutputTokens))
}

internal fun JsonObject.toUsage(): Usage = Usage(
    promptTokens = intOrNull("prompt_tokens") ?: 0,
    completionTokens = intOrNull("completion_tokens") ?: 0,
    totalTokens = intOrNull("total_tokens") ?: 0,
    cachedInputTokens = intOrNull("cached_input_tokens") ?: 0,
    reasoningOutputTokens = intOrNull("reasoning_output_tokens") ?: 0,
)

internal fun AgentError.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is AgentError.ModelError -> {
            put("type", JsonPrimitive("model_error")); put("retriable", JsonPrimitive(retriable))
        }
        is AgentError.ToolError -> {
            put("type", JsonPrimitive("tool_error")); put("tool_name", JsonPrimitive(toolName)); put("retriable", JsonPrimitive(retriable))
        }
        is AgentError.ParseError -> {
            put("type", JsonPrimitive("parse_error")); put("raw", JsonPrimitive(raw))
        }
        is AgentError.ToolNotFound -> {
            put("type", JsonPrimitive("tool_not_found")); put("tool_name", JsonPrimitive(toolName))
        }
        is AgentError.SkillError -> {
            put("type", JsonPrimitive("skill_error")); skillId?.let { put("skill_id", JsonPrimitive(it.value)) }; put("stage", JsonPrimitive(stage.name.lowercase()))
        }
        is AgentError.PreparationError -> {
            put("type", JsonPrimitive("preparation_error")); put("component", JsonPrimitive(component))
        }
        is AgentError.Timeout -> {
            put("type", JsonPrimitive("timeout")); put("stage", JsonPrimitive(stage)); put("elapsed_ms", JsonPrimitive(elapsedMs))
        }
        else -> put("type", JsonPrimitive("unknown_error"))
    }
    put("message", JsonPrimitive(message))
    cause?.message?.let { put("cause", JsonPrimitive(it)) }
}

internal fun IncompleteReason.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        IncompleteReason.MaxOutputTokens -> put("type", JsonPrimitive("max_output_tokens"))
        IncompleteReason.ContentFilter -> put("type", JsonPrimitive("content_filter"))
        IncompleteReason.Cancelled -> put("type", JsonPrimitive("cancelled"))
        is IncompleteReason.Other -> { put("type", JsonPrimitive("other")); put("code", JsonPrimitive(code)) }
    }
}

internal fun JsonObject.toIncompleteReason(): IncompleteReason = when (string("type")) {
    "max_output_tokens" -> IncompleteReason.MaxOutputTokens
    "content_filter" -> IncompleteReason.ContentFilter
    "cancelled" -> IncompleteReason.Cancelled
    "other" -> IncompleteReason.Other(string("code"))
    else -> error("unknown incomplete reason '${string("type")}'")
}

private fun TerminationReason.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is TerminationReason.MaxSteps -> { put("type", JsonPrimitive("max_steps")); put("max_steps", JsonPrimitive(maxSteps)) }
        is TerminationReason.MaxTokens -> { put("type", JsonPrimitive("max_tokens")); put("max_tokens", JsonPrimitive(maxTokens)) }
        is TerminationReason.RunBudgetSteps -> { put("type", JsonPrimitive("run_budget_steps")); put("max_total_steps", JsonPrimitive(maxTotalSteps)) }
        is TerminationReason.RunBudgetTokens -> { put("type", JsonPrimitive("run_budget_tokens")); put("max_total_tokens", JsonPrimitive(maxTotalTokens)) }
        is TerminationReason.Custom -> { put("type", JsonPrimitive("custom")); put("message", JsonPrimitive(message)) }
    }
}

internal fun AgentResult.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is AgentResult.Completed -> put("status", JsonPrimitive("completed"))
        is AgentResult.Incomplete -> { put("status", JsonPrimitive("incomplete")); put("reason", reason.toJson()) }
        is AgentResult.Terminated -> { put("status", JsonPrimitive("terminated")); put("reason", reason.toJson()) }
        is AgentResult.Failed -> { put("status", JsonPrimitive("failed")); put("error", error.toJson()) }
    }
    put("text", JsonPrimitive(text))
    put("message", message.toJson())
    put("usage", usage.toJson())
}

internal fun AgentEvent.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is AgentEvent.TextDelta -> { put("type", JsonPrimitive("text_delta")); put("text", JsonPrimitive(text)); itemRef?.let { put("item_ref", JsonPrimitive(it.value)) } }
        is AgentEvent.ReasoningDelta -> { put("type", JsonPrimitive("reasoning_delta")); put("text", JsonPrimitive(text)); itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }; put("kind", JsonPrimitive(kind.name.lowercase())) }
        is AgentEvent.Model -> {
            put("type", JsonPrimitive("model")); put("event", event.toJson()); put("step", JsonPrimitive(step))
            put("phase", JsonPrimitive(if (phase == org.koaks.framework.middleware.ModelCallPhase.Normal) "normal" else "structured_finalization"))
        }
        is AgentEvent.ToolCallRequested -> { put("type", JsonPrimitive("tool_call_requested")); put("call", call.toJson()) }
        is AgentEvent.ToolResult -> { put("type", JsonPrimitive("tool_result")); put("call_id", JsonPrimitive(callId)); put("output", JsonPrimitive(output)); put("is_error", JsonPrimitive(isError)) }
        is AgentEvent.ToolProgress -> {
            put("type", JsonPrimitive("tool_progress")); put("call_id", JsonPrimitive(callId)); put("progress", progress.toJson())
        }
        is AgentEvent.StepCompleted -> { put("type", JsonPrimitive("step_completed")); put("step", JsonPrimitive(step)) }
        is AgentEvent.Completed -> { put("type", JsonPrimitive("completed")); put("message", message.toJson()); put("usage", usage.toJson()) }
        is AgentEvent.Incomplete -> { put("type", JsonPrimitive("incomplete")); put("message", message.toJson()); put("usage", usage.toJson()); put("reason", reason.toJson()) }
        is AgentEvent.Terminated -> { put("type", JsonPrimitive("terminated")); put("message", message.toJson()); put("usage", usage.toJson()); put("reason", reason.toJson()) }
        is AgentEvent.Failed -> { put("type", JsonPrimitive("failed")); put("error", error.toJson()); put("usage", usage.toJson()) }
    }
}

private fun ToolProgress.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is ToolProgress.Output -> {
            put("type", JsonPrimitive("output")); put("text", JsonPrimitive(text))
            put("stream", JsonPrimitive(if (stream == ToolOutputStream.Stdout) "stdout" else "stderr"))
        }
        is ToolProgress.Status -> { put("type", JsonPrimitive("status")); put("message", JsonPrimitive(message)) }
        is ToolProgress.Custom -> { put("type", JsonPrimitive("custom")); put("kind", JsonPrimitive(kind)); put("payload", payload) }
    }
}

internal fun org.koaks.framework.model.ToolCall.toJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("arguments_json", JsonPrimitive(arguments))
    nativeId?.let { put("native_id", it.toJson()) }; nativeItemId?.let { put("native_item_id", it.toJson()) }
}

private fun ProviderScopedId.toJson(): JsonObject = buildJsonObject {
    put("provider_id", JsonPrimitive(providerId.value)); put("raw", JsonPrimitive(raw))
}

internal fun ModelItem.toJson(): JsonObject = buildJsonObject {
    put("ref", JsonPrimitive(ref.value))
    nativeId?.let { put("native_id", it.toJson()) }
    when (this@toJson) {
        is ModelItem.Message -> {
            put("type", JsonPrimitive("message")); put("role", JsonPrimitive(role.name.lowercase()))
            put("content", buildJsonArray { content.forEach { add(it.toJson()) } })
            refusal?.let { put("refusal", JsonPrimitive(it)) }
            put("annotations", buildJsonArray { annotations.forEach { add(it.toJson()) } })
        }
        is ModelItem.ToolCall -> {
            put("type", JsonPrimitive("tool_call")); put("name", JsonPrimitive(name)); put("arguments_json", JsonPrimitive(arguments))
            nativeItemId?.let { put("native_item_id", it.toJson()) }
        }
        is ModelItem.ToolResult -> {
            put("type", JsonPrimitive("tool_result")); put("call_ref", JsonPrimitive(callRef.value)); put("output", JsonPrimitive(output)); put("is_error", JsonPrimitive(isError))
        }
        is ModelItem.ReasoningSummary -> { put("type", JsonPrimitive("reasoning_summary")); put("text", JsonPrimitive(text)) }
        is ModelItem.ProviderItem -> {
            put("type", JsonPrimitive("provider_item")); put("provider_id", JsonPrimitive(providerId.value)); put("kind", JsonPrimitive(kind))
            put("display_text", JsonPrimitive(displayText)); put("replay", JsonPrimitive(replay.name.lowercase())); put("payload_base64", JsonPrimitive(payload.base64()))
        }
    }
}

private fun ContentPart.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is ContentPart.Text -> { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) }
        is ContentPart.Image -> { put("type", JsonPrimitive("image")); url?.let { put("url", JsonPrimitive(it)) }; base64?.let { put("base64", JsonPrimitive(it)) } }
        is ContentPart.Audio -> { put("type", JsonPrimitive("audio")); url?.let { put("url", JsonPrimitive(it)) }; base64?.let { put("base64", JsonPrimitive(it)) }; put("format", JsonPrimitive(format)) }
    }
}

internal fun Annotation.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is Annotation.UrlCitation -> { put("type", JsonPrimitive("url_citation")); put("url", JsonPrimitive(url)); title?.let { put("title", JsonPrimitive(it)) }; startIndex?.let { put("start_index", JsonPrimitive(it)) }; endIndex?.let { put("end_index", JsonPrimitive(it)) } }
        is Annotation.FileCitation -> { put("type", JsonPrimitive("file_citation")); put("file_id", JsonPrimitive(fileId)); filename?.let { put("filename", JsonPrimitive(it)) }; startIndex?.let { put("start_index", JsonPrimitive(it)) }; endIndex?.let { put("end_index", JsonPrimitive(it)) } }
        is Annotation.Generic -> { put("type", JsonPrimitive("generic")); put("kind", JsonPrimitive(kind)); put("payload", JsonPrimitive(payload)) }
    }
}

internal fun JsonObject.toModelItem(): ModelItem {
    val ref = stringOrNull("ref")?.let(::ItemRef) ?: ItemRef.generate()
    val nativeId = objectOrNull("native_id")?.toProviderScopedId()
    return when (string("type")) {
        "message" -> ModelItem.Message(
            ref = ref,
            nativeId = nativeId,
            role = Role.valueOf(string("role").uppercase()),
            content = arrayOrNull("content").orEmpty().map { it.jsonObject.toContentPart() },
            refusal = stringOrNull("refusal"),
            annotations = arrayOrNull("annotations").orEmpty().map { it.jsonObject.toAnnotation() },
        )
        "tool_call" -> ModelItem.ToolCall(ref, nativeId, string("name"), string("arguments_json"), objectOrNull("native_item_id")?.toProviderScopedId())
        "tool_result" -> ModelItem.ToolResult(ref, nativeId, ItemRef(string("call_ref")), string("output"), booleanOrNull("is_error") ?: false)
        "reasoning_summary" -> ModelItem.ReasoningSummary(ref, nativeId, string("text"))
        "provider_item" -> ModelItem.ProviderItem(
            ref, nativeId, ProviderId(string("provider_id")), string("kind"), string("display_text"),
            ReplayPolicy.valueOf(string("replay").replaceFirstChar { it.uppercase() }),
            requireNotNull(string("payload_base64").decodeBase64()) { "invalid provider item payload_base64" },
        )
        else -> error("unknown model item type '${string("type")}'")
    }
}

internal fun JsonObject.toProviderScopedId() = ProviderScopedId(ProviderId(string("provider_id")), string("raw"))

private fun JsonObject.toContentPart(): ContentPart = when (string("type")) {
    "text" -> ContentPart.Text(string("text"))
    "image" -> ContentPart.Image(stringOrNull("url"), stringOrNull("base64"))
    "audio" -> ContentPart.Audio(stringOrNull("url"), stringOrNull("base64"), string("format"))
    else -> error("unknown content part type '${string("type")}'")
}

internal fun JsonObject.toAnnotation(): Annotation = when (string("type")) {
    "url_citation" -> Annotation.UrlCitation(string("url"), stringOrNull("title"), intOrNull("start_index"), intOrNull("end_index"))
    "file_citation" -> Annotation.FileCitation(string("file_id"), stringOrNull("filename"), intOrNull("start_index"), intOrNull("end_index"))
    "generic" -> Annotation.Generic(string("kind"), string("payload"))
    else -> error("unknown annotation type '${string("type")}'")
}

internal fun ProviderCheckpoint.toJson(): JsonObject = buildJsonObject {
    put("provider_id", JsonPrimitive(providerId.value)); put("codec_version", JsonPrimitive(codecVersion))
    put("basis", buildJsonObject { put("item_count", JsonPrimitive(basis.itemCount)); put("digest", JsonPrimitive(basis.digest)) })
    put(
        "scope",
        JsonPrimitive(
            when (scope) {
                CheckpointScope.InRun -> "in_run"
                CheckpointScope.CrossTurn -> "cross_turn"
            },
        ),
    )
    put("payload_base64", JsonPrimitive(payload.base64()))
    expiresAtEpochMs?.let { put("expires_at_epoch_ms", JsonPrimitive(it)) }
}

internal fun JsonObject.toCheckpoint(): ProviderCheckpoint = ProviderCheckpoint(
    providerId = ProviderId(string("provider_id")),
    codecVersion = intOrNull("codec_version") ?: error("'codec_version' is required"),
    basis = objectOrNull("basis")!!.let { TranscriptBasis(it.intOrNull("item_count")!!, it.string("digest")) },
    scope = when (string("scope")) {
        "in_run" -> CheckpointScope.InRun
        "cross_turn" -> CheckpointScope.CrossTurn
        else -> error("unknown checkpoint scope '${string("scope")}'")
    },
    payload = requireNotNull(string("payload_base64").decodeBase64()),
    expiresAtEpochMs = longOrNull("expires_at_epoch_ms"),
)

internal fun MemoryView.toJson(): JsonObject = buildJsonObject {
    put("transcript", buildJsonArray { transcript.forEach { add(it.toJson()) } }); checkpoint?.let { put("checkpoint", it.toJson()) }
}

internal fun JsonObject.toMemoryView(): MemoryView = MemoryView(
    transcript = arrayOrNull("transcript").orEmpty().map { it.jsonObject.toModelItem() },
    checkpoint = objectOrNull("checkpoint")?.toCheckpoint(),
)

internal fun ConversationTurn.toJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id)); put("status", status.toJson()); put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    checkpoint?.let { put("checkpoint", it.toJson()) }; put("usage", usage.toJson())
}

private fun TurnStatus.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        TurnStatus.Completed -> put("type", JsonPrimitive("completed"))
        is TurnStatus.Interrupted -> { put("type", JsonPrimitive("interrupted")); put("reason", reason.toJson()); put("pending", pending.toJson()) }
    }
}

private fun InterruptReason.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        InterruptReason.Cancelled -> put("type", JsonPrimitive("cancelled"))
        InterruptReason.Failed -> put("type", JsonPrimitive("failed"))
        is InterruptReason.Incomplete -> { put("type", JsonPrimitive("incomplete")); put("reason", reason.toJson()) }
        is InterruptReason.Policy -> { put("type", JsonPrimitive("policy")); put("detail", JsonPrimitive(detail)) }
    }
}

private fun PendingWork.toJson(): JsonObject = buildJsonObject {
    put("unresolved_calls", buildJsonArray { unresolvedCalls.forEach { add(JsonPrimitive(it.value)) } })
    partialText?.let { put("partial_text", JsonPrimitive(it)) }; partialItem?.let { put("partial_item", JsonPrimitive(it.value)) }
}

internal fun AgentState.toJson(): JsonObject = buildJsonObject {
    put("items", buildJsonArray { items.forEach { add(it.toJson()) } }); instructions?.let { put("instructions", JsonPrimitive(it)) }
    checkpoint?.let { put("checkpoint", it.toJson()) }; put("global_step", JsonPrimitive(globalStep)); put("local_step", JsonPrimitive(localStep))
    put("usage", usage.toJson()); put("active_agent_name", JsonPrimitive(activeAgentName))
}

internal fun AcbSnapshot.toJson(): JsonObject = buildJsonObject {
    put("run_id", JsonPrimitive(runId.value.toString())); put("agent_id", JsonPrimitive(agentId.value)); put("agent_name", JsonPrimitive(agentName))
    threadId?.let { put("thread_id", JsonPrimitive(it.value)) }; turnId?.let { put("turn_id", JsonPrimitive(it.value.toString())) }
    put("state", JsonPrimitive(state.name.lowercase())); put("priority", JsonPrimitive(priority)); parent?.let { put("parent", JsonPrimitive(it.value.toString())) }
    correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
    put("children", buildJsonArray { children.forEach { add(JsonPrimitive(it.value.toString())) } }); put("accepting_children", JsonPrimitive(acceptingChildren))
    put("usage", usage.toJson()); put("steps_completed", JsonPrimitive(stepsCompleted)); put("tool_calls", JsonPrimitive(toolCalls)); put("elapsed_ms", JsonPrimitive(elapsedMillis))
    error?.let { put("error", it.toJson()) }
}

internal fun RunEventEnvelope.toJson(): JsonObject = buildJsonObject {
    put("run_id", JsonPrimitive(runId.value.toString())); put("agent_id", JsonPrimitive(agentId.value))
    threadId?.let { put("thread_id", JsonPrimitive(it.value)) }; turnId?.let { put("turn_id", JsonPrimitive(it.value.toString())) }
    correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
    put("sequence", JsonPrimitive(sequence)); put("timestamp_epoch_ms", JsonPrimitive(timestampEpochMillis))
    when (val value = payload) {
        is RunEventPayload.Agent -> { put("kind", JsonPrimitive("agent")); put("event", value.event.toJson()) }
        is RunEventPayload.Lifecycle -> { put("kind", JsonPrimitive("lifecycle")); put("event", value.event.toJson()) }
        is RunEventPayload.HistoryGap -> {
            put("kind", JsonPrimitive("history_gap")); put("requested_after", JsonPrimitive(value.requestedAfter))
            put("oldest_available", JsonPrimitive(value.oldestAvailable))
        }
    }
}

internal fun ThreadSnapshot.toJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id.value)); put("memory_provider_id", JsonPrimitive(memoryProviderId.value))
    put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it.value)) } })
    activeTurn?.let { put("active_turn", JsonPrimitive(it.value.toString())) }
    put("queued_turns", buildJsonArray { queuedTurns.forEach { add(JsonPrimitive(it.value.toString())) } })
}

internal fun RuntimeMetrics.toJson(): JsonObject = buildJsonObject {
    put("total", JsonPrimitive(total)); put("created", JsonPrimitive(created)); put("thread_queued", JsonPrimitive(threadQueued)); put("ready", JsonPrimitive(ready))
    put("running", JsonPrimitive(running)); put("waiting", JsonPrimitive(waiting)); put("suspended", JsonPrimitive(suspended)); put("committing", JsonPrimitive(committing))
    put("finished", JsonPrimitive(finished)); put("failed", JsonPrimitive(failed)); put("cancelled", JsonPrimitive(cancelled)); put("total_tokens", JsonPrimitive(totalTokens))
    put("total_steps", JsonPrimitive(totalSteps)); put("total_tool_calls", JsonPrimitive(totalToolCalls))
}

internal fun RuntimeEvent.toJson(): JsonObject = buildJsonObject {
    fun base(type: String, run: String?, agent: String?, thread: String?, turn: String?) {
        put("type", JsonPrimitive(type)); run?.let { put("run_id", JsonPrimitive(it)) }; agent?.let { put("agent_id", JsonPrimitive(it)) }
        thread?.let { put("thread_id", JsonPrimitive(it)) }; turn?.let { put("turn_id", JsonPrimitive(it)) }
    }
    when (this@toJson) {
        is RuntimeEvent.Spawned -> { base("spawned", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("agent_name", JsonPrimitive(agentName)); put("priority", JsonPrimitive(priority)); parent?.let { put("parent", JsonPrimitive(it.value.toString())) } }
        is RuntimeEvent.Running -> { base("running", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("agent_name", JsonPrimitive(agentName)) }
        is RuntimeEvent.Waiting -> base("waiting", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.Suspended -> base("suspended", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.Resumed -> base("resumed", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.Finished -> { base("finished", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("usage", usage.toJson()) }
        is RuntimeEvent.Incomplete -> { base("incomplete", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("reason", reason.toJson()); put("usage", usage.toJson()) }
        is RuntimeEvent.Terminated -> { base("terminated", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("reason", reason.toJson()) }
        is RuntimeEvent.Failed -> { base("failed", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("error", error.toJson()) }
        is RuntimeEvent.Cancelled -> base("cancelled", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.UnhandledChildFailure -> { base("unhandled_child_failure", childRunId.value.toString(), childAgentId.value, null, null); put("parent_run_id", JsonPrimitive(parentRunId.value.toString())); put("error", error.toJson()) }
        is RuntimeEvent.SideEffectRollback -> base("side_effect_rollback", runId.value.toString(), agentId.value, threadId.value, turnId.value.toString())
        is RuntimeEvent.Retrying -> { base("retrying", runId?.value?.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("agent_name", JsonPrimitive(agentName)); put("attempt", JsonPrimitive(attempt)); put("delay_ms", JsonPrimitive(delayMillis)) }
        is RuntimeEvent.CircuitOpen -> { base("circuit_open", runId?.value?.toString(), agentId.value, threadId?.value, turnId?.value?.toString()); put("agent_name", JsonPrimitive(agentName)) }
    }
}
