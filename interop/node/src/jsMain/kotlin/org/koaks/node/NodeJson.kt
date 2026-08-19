package org.koaks.node

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.AgentState
import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.tool.ToolProgress
import org.koaks.json.KoaksWireJson
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.RunEventEnvelope
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.observe.RuntimeMetrics
import org.koaks.runtime.thread.ThreadSnapshot

internal val nodeJson = KoaksWireJson.json

internal fun parseObject(text: String): JsonObject = KoaksWireJson.parseObject(text)

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

internal fun Usage.toJson(): JsonObject = KoaksWireJson.encodeUsage(this)
internal fun JsonObject.toUsage(): Usage = KoaksWireJson.decodeUsage(this)
internal fun AgentError.toJson(): JsonObject = KoaksWireJson.encodeAgentError(this)
internal fun IncompleteReason.toJson(): JsonObject = KoaksWireJson.encodeIncompleteReason(this)
internal fun TerminationReason.toJson(): JsonObject = KoaksWireJson.encodeTerminationReason(this)
internal fun AgentResult.toJson(): JsonObject = KoaksWireJson.encodeAgentResult(this)
internal fun AgentEvent.toJson(): JsonObject = KoaksWireJson.encodeAgentEvent(this)
internal fun ToolProgress.toJson(): JsonObject = KoaksWireJson.encodeToolProgress(this)
internal fun ToolCall.toJson(): JsonObject = KoaksWireJson.encodeToolCall(this)
internal fun JsonObject.toToolCall(): ToolCall = KoaksWireJson.decodeToolCall(this)
internal fun ModelItem.toJson(): JsonObject = KoaksWireJson.encodeModelItem(this)
internal fun Annotation.toJson(): JsonObject = KoaksWireJson.encodeAnnotation(this)
internal fun JsonObject.toModelItem(): ModelItem = KoaksWireJson.decodeModelItem(this)
internal fun JsonObject.toAnnotation(): Annotation = KoaksWireJson.decodeAnnotation(this)
internal fun ProviderCheckpoint.toJson(): JsonObject = KoaksWireJson.encodeProviderCheckpoint(this)
internal fun JsonObject.toCheckpoint(): ProviderCheckpoint = KoaksWireJson.decodeProviderCheckpoint(this)
internal fun MemoryView.toJson(): JsonObject = KoaksWireJson.encodeMemoryView(this)
internal fun JsonObject.toMemoryView(): MemoryView = KoaksWireJson.decodeMemoryView(this)
internal fun ConversationTurn.toJson(): JsonObject = KoaksWireJson.encodeConversationTurn(this)
internal fun AgentState.toJson(): JsonObject = KoaksWireJson.encodeAgentState(this)
internal fun AcbSnapshot.toJson(): JsonObject = KoaksWireJson.encodeAcbSnapshot(this)
internal fun RunEventEnvelope.toJson(): JsonObject = KoaksWireJson.encodeRunEvent(this)
internal fun ThreadSnapshot.toJson(): JsonObject = KoaksWireJson.encodeThreadSnapshot(this)
internal fun RuntimeMetrics.toJson(): JsonObject = KoaksWireJson.encodeRuntimeMetrics(this)
internal fun RuntimeEvent.toJson(): JsonObject = KoaksWireJson.encodeRuntimeEvent(this)
internal fun ModelEvent.toJson(): JsonObject = KoaksWireJson.encodeModelEvent(this)
internal fun JsonObject.toModelEvent(): ModelEvent = KoaksWireJson.decodeModelEvent(this)
