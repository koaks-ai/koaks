package org.koaks.framework.mcp.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import org.koaks.framework.mcp.McpToolGateway
import org.koaks.framework.mcp.entity.InitRequest
import org.koaks.framework.mcp.entity.InitResponse
import org.koaks.framework.mcp.entity.InitializedRequest
import org.koaks.framework.mcp.entity.ListToolsResult
import org.koaks.framework.mcp.entity.McpClientConfig
import org.koaks.framework.mcp.entity.McpTool
import org.koaks.framework.mcp.entity.Prompt
import org.koaks.framework.mcp.entity.PromptDetail
import org.koaks.framework.mcp.entity.Resource
import org.koaks.framework.mcp.entity.ResourceData
import org.koaks.framework.mcp.entity.ToolResponse
import org.koaks.framework.model.TypeAdapter
import org.koaks.framework.net.HttpClientConfig
import org.koaks.framework.net.KtorHttpClient

@Serializable
private data class CallToolParams(val name: String, val arguments: JsonObject)

class DefaultMcpClient(
    private val mcpUrl: String,
    private val mcpClientConfig: McpClientConfig,
    private val customHeaders: Map<String, String> = emptyMap(),
) : McpClient, McpToolGateway {

    private val logger = KotlinLogging.logger {}

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var httpClient = KtorHttpClient(
        HttpClientConfig(
            baseUrl = mcpUrl,
            customHeaders = customHeaders
        )
    )

    private val transport = HttpMcpTransport(httpClient)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            val initResponse = initialize(InitRequest(id = mcpClientConfig.id))
            if (initResponse.error == null) {
                initialized(InitializedRequest())
                logger.info { "MCP client initialized successfully, @$mcpUrl" }
            } else {
                logger.error { "MCP client initialization failed: ${initResponse.error}" }
            }
        }
    }

    override suspend fun initialize(request: InitRequest): InitResponse {
        val typeAdapter = TypeAdapter(InitRequest.serializer(), InitResponse.serializer())
        return httpClient.postAsObject(request, typeAdapter).fold(
            onSuccess = {
                if (it.error == null) {
                    logger.info { "mcp server initialized successfully" }
                } else {
                    logger.error { "[server internal error] failed to initialize mcp server, url: $mcpUrl, error:${it.error}" }
                }
                it
            },
            onFailure = {
                logger.error { "failed to initialize mcp server, url: $mcpUrl, error:${it.message}" }
                InitResponse()
            }
        )
    }

    override suspend fun initialized(request: InitializedRequest) {
        httpClient.postAsString(request, InitializedRequest.serializer()).fold(
            onSuccess = {
                logger.debug { "mcp client initialized successfully" }
            },
            onFailure = {
                logger.error { "failed to initialized mcp client, url: $mcpUrl, error:${it.message}" }
            }
        )
    }

    override suspend fun listResources(): List<Resource> {
        TODO("Not yet implemented")
    }

    override suspend fun readResource(uri: String): ResourceData {
        TODO("Not yet implemented")
    }

    override suspend fun subscribeResource(uri: String) {
        TODO("Not yet implemented")
    }

    override suspend fun listTools(): List<McpTool> {
        return transport.request<JsonElement, ListToolsResult>(
            method = "tools/list",
            params = null,
            requestSerializer = JsonElement.serializer(),
            responseSerializer = ListToolsResult.serializer(),
        ).tools
    }

    override suspend fun callTool(
        name: String,
        arguments: JsonElement
    ): ToolResponse {
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        return transport.request(
            method = "tools/call",
            params = CallToolParams(name, args),
            requestSerializer = CallToolParams.serializer(),
            responseSerializer = ToolResponse.serializer(),
        )
    }

    /** [McpToolGateway] entry point: invoke a tool with raw JSON arguments, return the result as a string. */
    override suspend fun callTool(name: String, argumentsJson: String): String {
        val args = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())
        return callTool(name, args).result.toString()
    }

    override suspend fun listPrompts(): List<Prompt> {
        TODO("Not yet implemented")
    }

    override suspend fun getPrompt(name: String): PromptDetail {
        TODO("Not yet implemented")
    }

    override suspend fun sendLog(level: String, message: String) {
        TODO("Not yet implemented")
    }

}