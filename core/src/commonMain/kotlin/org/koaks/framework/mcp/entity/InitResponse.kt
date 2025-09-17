package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable

@Serializable
data class InitResponse(
    var jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: Result? = null,
    val error: McpError? = null
) {
    @Serializable
    data class Result(
        val protocolVersion: String,
        val capabilities: Capabilities,
        val serverInfo: ServerInfo,
        val instructions: String? = null
    )

    @Serializable
    data class Capabilities(
        val logging: Logging,
        val prompts: Prompts,
        val resources: Resources,
        val tools: Tools
    )

    @Serializable
    class Logging

    @Serializable
    data class Prompts(
        val listChanged: Boolean
    )

    @Serializable
    data class Resources(
        val subscribe: Boolean,
        val listChanged: Boolean
    )

    @Serializable
    data class Tools(
        val listChanged: Boolean
    )

    @Serializable
    data class ServerInfo(
        val name: String,
        val version: String
    )
}
