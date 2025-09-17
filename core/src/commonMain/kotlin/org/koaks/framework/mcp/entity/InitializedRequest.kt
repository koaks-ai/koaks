package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable

@Serializable
data class InitializedRequest(
    val jsonrpc: String = "2.0",
    val method: String = "notifications/initialized",
)
