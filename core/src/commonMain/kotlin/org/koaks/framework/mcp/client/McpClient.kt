package org.koaks.framework.mcp.client

import org.koaks.framework.mcp.InitRequest
import org.koaks.framework.mcp.InitResponse
import org.koaks.framework.mcp.InitializedRequest


interface McpClient {

    suspend fun initialize(request: InitRequest): InitResponse
    suspend fun initialized(request: InitializedRequest)

}