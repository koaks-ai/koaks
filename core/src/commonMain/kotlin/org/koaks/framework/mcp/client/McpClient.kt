package org.koaks.framework.mcp.client

import kotlinx.serialization.json.JsonElement
import org.koaks.framework.mcp.entity.InitRequest
import org.koaks.framework.mcp.entity.InitResponse
import org.koaks.framework.mcp.entity.InitializedRequest
import org.koaks.framework.mcp.entity.McpTool
import org.koaks.framework.mcp.entity.Prompt
import org.koaks.framework.mcp.entity.PromptDetail
import org.koaks.framework.mcp.entity.Resource
import org.koaks.framework.mcp.entity.ResourceData
import org.koaks.framework.mcp.entity.ToolResponse


interface McpClient {

    suspend fun initialize(request: InitRequest): InitResponse
    suspend fun initialized(request: InitializedRequest)
    suspend fun listResources(): List<Resource>
    suspend fun readResource(uri: String): ResourceData
    suspend fun subscribeResource(uri: String)

    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: JsonElement): ToolResponse

    suspend fun listPrompts(): List<Prompt>
    suspend fun getPrompt(name: String): PromptDetail

    suspend fun sendLog(level: String, message: String)

}