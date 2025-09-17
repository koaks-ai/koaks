package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable

@Serializable
data class Prompt(
    val name: String,
    val description: String? = null
)
