package org.koaks.json

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.memory.MemoryView

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
