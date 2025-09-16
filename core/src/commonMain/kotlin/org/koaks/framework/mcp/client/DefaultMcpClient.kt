package org.koaks.framework.mcp.client

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.mcp.InitRequest
import org.koaks.framework.mcp.InitResponse
import org.koaks.framework.mcp.InitializedRequest
import org.koaks.framework.model.TypeAdapter
import org.koaks.framework.net.HttpClientConfig
import org.koaks.framework.net.KtorHttpClient

class DefaultMcpClient(
    private val mcpUrl: String
) : McpClient {

    private val logger = KotlinLogging.logger {}

    private var httpClient = KtorHttpClient(
        HttpClientConfig(baseUrl = mcpUrl)
    )

    private var initializeLock = false
    private var initializedLock = false

    fun checkInitialized() = (initializeLock && initializedLock)

    override suspend fun initialize(request: InitRequest): InitResponse {
        val typeAdapter = TypeAdapter(InitRequest.serializer(), InitResponse.serializer())
        return httpClient.postAsObject(request, typeAdapter).fold(
            onSuccess = {
                logger.debug { "MCP initialized successfully" }
                it
            },
            onFailure = {
                logger.error { "Failed to initialize MCP, url: $mcpUrl, error:${it.message}" }
                InitResponse()
            }
        )
    }

    override suspend fun initialized(request: InitializedRequest) {
        httpClient.postAsString(request, InitializedRequest.serializer()).fold(
            onSuccess = {
                logger.debug { "MCP initialized successfully" }
            },
            onFailure = {
                logger.error { "Failed to initialize MCP, url: $mcpUrl, error:${it.message}" }
            }
        )
    }

}