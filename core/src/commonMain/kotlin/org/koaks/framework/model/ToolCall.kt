package org.koaks.framework.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single tool invocation requested by the model.
 *
 * @property id provider-assigned identifier, used to correlate the call with its result.
 * @property name the name of the [org.koaks.framework.tool.Tool] to invoke.
 * @property arguments the raw JSON arguments string, decoded by the tool's input serializer.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
