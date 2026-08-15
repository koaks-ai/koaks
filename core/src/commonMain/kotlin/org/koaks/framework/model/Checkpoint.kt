package org.koaks.framework.model

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

data class TranscriptBasis(
    val itemCount: Int,
    val digest: String,
) {
    companion object {
        fun of(items: List<ModelItem>): TranscriptBasis {
            val canonical = Buffer()
            canonical.writeInt(items.size)
            items.forEach(canonical::writeItem)
            return TranscriptBasis(items.size, "sha256:${canonical.snapshot().sha256().hex()}")
        }
    }
}

private fun Buffer.writeItem(item: ModelItem) {
    writeString(item.ref.value)
    writeNativeId(item.nativeId)
    when (item) {
        is ModelItem.Message -> {
            writeString("message")
            writeString(item.role.name)
            writeInt(item.content.size)
            item.content.forEach(::writeContentPart)
            writeString(item.refusal)
            writeInt(item.annotations.size)
            item.annotations.forEach(::writeAnnotation)
        }
        is ModelItem.ToolCall -> {
            writeString("tool_call")
            writeString(item.name)
            writeString(item.arguments)
            writeNativeId(item.nativeItemId)
        }
        is ModelItem.ToolResult -> {
            writeString("tool_result")
            writeString(item.callRef.value)
            writeString(item.output)
            writeByte(if (item.isError) 1 else 0)
        }
        is ModelItem.ReasoningSummary -> {
            writeString("reasoning_summary")
            writeString(item.text)
        }
        is ModelItem.ProviderItem -> {
            writeString("provider_item")
            writeString(item.providerId.value)
            writeString(item.kind)
            writeString(item.displayText)
            writeString(item.replay.name)
            writeBytes(item.payload)
        }
    }
}

private fun Buffer.writeContentPart(part: ContentPart) {
    when (part) {
        is ContentPart.Text -> {
            writeString("text")
            writeString(part.text)
        }
        is ContentPart.Image -> {
            writeString("image")
            writeString(part.url)
            writeString(part.base64)
        }
        is ContentPart.Audio -> {
            writeString("audio")
            writeString(part.url)
            writeString(part.base64)
            writeString(part.format)
        }
    }
}

private fun Buffer.writeAnnotation(annotation: Annotation) {
    when (annotation) {
        is Annotation.UrlCitation -> {
            writeString("url_citation")
            writeString(annotation.url)
            writeString(annotation.title)
            writeNullableInt(annotation.startIndex)
            writeNullableInt(annotation.endIndex)
        }
        is Annotation.FileCitation -> {
            writeString("file_citation")
            writeString(annotation.fileId)
            writeString(annotation.filename)
            writeNullableInt(annotation.startIndex)
            writeNullableInt(annotation.endIndex)
        }
        is Annotation.Generic -> {
            writeString("generic")
            writeString(annotation.kind)
            writeString(annotation.payload)
        }
    }
}

private fun Buffer.writeNativeId(id: ProviderScopedId?) {
    writeString(id?.providerId?.value)
    writeString(id?.raw)
}

private fun Buffer.writeNullableInt(value: Int?) {
    if (value == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeInt(value)
    }
}

private fun Buffer.writeString(value: String?) {
    if (value == null) {
        writeInt(-1)
        return
    }
    writeBytes(value.encodeUtf8())
}

private fun Buffer.writeBytes(value: ByteString) {
    writeInt(value.size)
    write(value)
}

enum class CheckpointScope {
    /** Lives only for the current run (reasoning continuity across tool steps). */
    InRun,

    /** May be persisted across turns; sensitive, opt-in. */
    CrossTurn,
}

/**
 * Opaque provider-native continuation state. Core never interprets [payload];
 * it only compares [basis] against the repaired transcript.
 */
data class ProviderCheckpoint(
    val providerId: ProviderId,
    val codecVersion: Int,
    val basis: TranscriptBasis,
    val scope: CheckpointScope,
    val payload: ByteString,
    val expiresAtEpochMs: Long? = null,
) {
    fun matches(items: List<ModelItem>): Boolean {
        val actual = TranscriptBasis.of(items)
        return basis.itemCount == actual.itemCount && basis.digest == actual.digest
    }
}

fun ProviderCheckpoint?.takeIfValidFor(items: List<ModelItem>, nowEpochMs: Long? = null): ProviderCheckpoint? {
    val checkpoint = this ?: return null
    val expires = checkpoint.expiresAtEpochMs
    if (nowEpochMs != null && expires != null && expires <= nowEpochMs) return null
    return checkpoint.takeIf { it.matches(items) }
}
