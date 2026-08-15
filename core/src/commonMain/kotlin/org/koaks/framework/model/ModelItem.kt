package org.koaks.framework.model

import okio.ByteString

/**
 * Provider-neutral conversation item. Memory, the agent loop, and providers all
 * speak this type. Provider-specific native state is carried in [ProviderItem].
 */
sealed interface ModelItem {
    val ref: ItemRef
    val nativeId: ProviderScopedId?

    data class Message(
        override val ref: ItemRef = ItemRef.generate("msg"),
        override val nativeId: ProviderScopedId? = null,
        val role: Role,
        val content: List<ContentPart>,
        val refusal: String? = null,
        val annotations: List<Annotation> = emptyList(),
    ) : ModelItem {
        val text: String
            get() = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    }

    data class ToolCall(
        override val ref: ItemRef = ItemRef.generate("call"),
        override val nativeId: ProviderScopedId? = null,
        val name: String,
        val arguments: String,
    ) : ModelItem

    data class ToolResult(
        override val ref: ItemRef = ItemRef.generate("result"),
        override val nativeId: ProviderScopedId? = null,
        val callRef: ItemRef,
        val output: String,
        val isError: Boolean = false,
    ) : ModelItem

    data class ReasoningSummary(
        override val ref: ItemRef = ItemRef.generate("reason"),
        override val nativeId: ProviderScopedId? = null,
        val text: String,
    ) : ModelItem

    /**
     * Lossless envelope for a provider-native item: encrypted reasoning, thinking
     * signatures, compaction items, unknown future types.
     */
    data class ProviderItem(
        override val ref: ItemRef = ItemRef.generate("ext"),
        override val nativeId: ProviderScopedId? = null,
        val providerId: ProviderId,
        val kind: String,
        val displayText: String,
        val replay: ReplayPolicy,
        val payload: ByteString,
    ) : ModelItem

    companion object {
        fun system(text: String, ref: ItemRef = ItemRef.generate("msg")): Message =
            Message(ref = ref, role = Role.SYSTEM, content = listOf(ContentPart.Text(text)))

        fun user(text: String, ref: ItemRef = ItemRef.generate("msg")): Message =
            Message(ref = ref, role = Role.USER, content = listOf(ContentPart.Text(text)))

        fun assistant(
            text: String,
            ref: ItemRef = ItemRef.generate("msg"),
            nativeId: ProviderScopedId? = null,
            refusal: String? = null,
            annotations: List<Annotation> = emptyList(),
        ): Message = Message(
            ref = ref,
            nativeId = nativeId,
            role = Role.ASSISTANT,
            content = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)),
            refusal = refusal,
            annotations = annotations,
        )
    }
}

fun ModelItem.displayText(): String = when (this) {
    is ModelItem.Message -> text
    is ModelItem.ToolCall -> "tool call $name($arguments)"
    is ModelItem.ToolResult -> output
    is ModelItem.ReasoningSummary -> text
    is ModelItem.ProviderItem -> displayText
}

fun ModelItem.isUserTurnBoundary(): Boolean =
    this is ModelItem.Message && role == Role.USER

fun ModelItem.isSystem(): Boolean =
    this is ModelItem.Message && role == Role.SYSTEM
