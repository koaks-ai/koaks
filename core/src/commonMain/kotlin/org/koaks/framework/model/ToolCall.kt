package org.koaks.framework.model

import kotlinx.serialization.Serializable

/**
 * A single tool invocation requested by the model, used by the agent loop to dispatch
 * [org.koaks.framework.tool.Tool] execution. [id] is the core [ItemRef] value used to
 * correlate the call with its result; [nativeId] is the provider-assigned wire id.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val nativeId: ProviderScopedId? = null,
) {
    val ref: ItemRef get() = ItemRef(id)

    fun toItem(): ModelItem.ToolCall = ModelItem.ToolCall(
        ref = ref,
        nativeId = nativeId,
        name = name,
        arguments = arguments,
    )
}

fun ModelItem.ToolCall.toDispatchCall(): ToolCall = ToolCall(
    id = ref.value,
    name = name,
    arguments = arguments,
    nativeId = nativeId,
)
