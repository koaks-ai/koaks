package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable

@Serializable
data class PromptDetail(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument>
)

@Serializable
data class PromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)