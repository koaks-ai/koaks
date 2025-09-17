package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable

@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: Data
) {
    @Serializable
    data class Data(
        val supported: List<String>,
        val requested: String
    )
}