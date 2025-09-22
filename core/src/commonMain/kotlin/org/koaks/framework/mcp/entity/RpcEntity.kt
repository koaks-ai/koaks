package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RpcEnvelope(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class RpcResponse<T>(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: T? = null,
    val error: RpcError? = null
)

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)