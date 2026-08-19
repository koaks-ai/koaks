package org.koaks.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.AgentState
import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.tool.ToolProgress
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.RunEventEnvelope
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.observe.RuntimeMetrics
import org.koaks.runtime.thread.ThreadSnapshot

/**
 * Stable platform-neutral facade for the Koaks interop JSON wire format.
 *
 * This is intentionally separate from the framework domain model and from KAP.
 */
object KoaksWireJson {
    val json: Json get() = wireJson

    fun parseObject(text: String): JsonObject = text.decodeWireObject()

    fun encodeAgentEvent(event: AgentEvent): JsonObject = event.toWireJson()
    fun encodeAgentResult(result: AgentResult): JsonObject = result.toWireJson()
    fun encodeAgentState(state: AgentState): JsonObject = state.toWireJson()
    fun encodeToolProgress(progress: ToolProgress): JsonObject = progress.toWireJson()
    fun encodeIncompleteReason(reason: IncompleteReason): JsonObject = reason.toWireJson()
    fun encodeTerminationReason(reason: TerminationReason): JsonObject = reason.toWireJson()

    fun encodeModelEvent(event: ModelEvent): JsonObject = event.toWireJson()
    fun decodeModelEvent(value: JsonObject): ModelEvent = value.toModelEvent()

    fun encodeRunEvent(envelope: RunEventEnvelope): JsonObject = envelope.toWireJson()
    fun encodeRuntimeEvent(event: RuntimeEvent): JsonObject = event.toWireJson()
    fun encodeAcbSnapshot(snapshot: AcbSnapshot): JsonObject = snapshot.toWireJson()
    fun encodeThreadSnapshot(snapshot: ThreadSnapshot): JsonObject = snapshot.toWireJson()
    fun encodeRuntimeMetrics(metrics: RuntimeMetrics): JsonObject = metrics.toWireJson()

    fun encodeModelItem(item: ModelItem): JsonObject = item.toWireJson()
    fun decodeModelItem(value: JsonObject): ModelItem = value.toModelItem()

    fun encodeToolCall(call: ToolCall): JsonObject = call.toWireJson()
    fun decodeToolCall(value: JsonObject): ToolCall = value.toToolCall()

    fun encodeAnnotation(annotation: Annotation): JsonObject = annotation.toWireJson()
    fun decodeAnnotation(value: JsonObject): Annotation = value.toAnnotation()

    fun encodeProviderCheckpoint(checkpoint: ProviderCheckpoint): JsonObject = checkpoint.toWireJson()
    fun decodeProviderCheckpoint(value: JsonObject): ProviderCheckpoint = value.toCheckpoint()

    fun encodeMemoryView(view: MemoryView): JsonObject = view.toWireJson()
    fun decodeMemoryView(value: JsonObject): MemoryView = value.toMemoryView()
    fun encodeConversationTurn(turn: ConversationTurn): JsonObject = turn.toWireJson()

    fun encodeUsage(usage: Usage): JsonObject = usage.toWireJson()
    fun decodeUsage(value: JsonObject): Usage = value.toUsage()

    fun encodeAgentError(error: AgentError): JsonObject = error.toWireJson()
}
