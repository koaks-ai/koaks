package org.koaks.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.InterruptReason
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.memory.PendingWork
import org.koaks.framework.memory.TurnStatus
import org.koaks.framework.model.ItemRef

internal fun MemoryView.toWireJson(): JsonObject = buildJsonObject {
    put("transcript", buildJsonArray { transcript.forEach { add(it.toWireJson()) } })
    checkpoint?.let { put("checkpoint", it.toWireJson()) }
}

internal fun JsonObject.toMemoryView(): MemoryView = MemoryView(
    transcript = optionalArray("transcript").orEmpty().map {
        (it as? JsonObject ?: error("memory transcript item must be an object")).toModelItem()
    },
    checkpoint = optionalObject("checkpoint")?.toCheckpoint(),
)

internal fun ConversationTurn.toWireJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("status", status.toWireJson())
    put("items", buildJsonArray { items.forEach { add(it.toWireJson()) } })
    checkpoint?.let { put("checkpoint", it.toWireJson()) }
    put("usage", usage.toWireJson())
}

private fun TurnStatus.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        TurnStatus.Completed -> put("type", JsonPrimitive("completed"))
        is TurnStatus.Interrupted -> {
            put("type", JsonPrimitive("interrupted"))
            put("reason", reason.toWireJson())
            put("pending", pending.toWireJson())
        }
    }
}

private fun InterruptReason.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        InterruptReason.Cancelled -> put("type", JsonPrimitive("cancelled"))
        InterruptReason.Failed -> put("type", JsonPrimitive("failed"))
        is InterruptReason.Incomplete -> {
            put("type", JsonPrimitive("incomplete"))
            put("reason", reason.toWireJson())
        }
        is InterruptReason.Policy -> {
            put("type", JsonPrimitive("policy"))
            put("detail", JsonPrimitive(detail))
        }
    }
}

private fun PendingWork.toWireJson(): JsonObject = buildJsonObject {
    put("unresolved_calls", buildJsonArray { unresolvedCalls.forEach { add(JsonPrimitive(it.value)) } })
    partialText?.let { put("partial_text", JsonPrimitive(it)) }
    partialItem?.let { put("partial_item", JsonPrimitive(it.value)) }
}
