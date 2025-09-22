package org.koaks.framework.mcp.client

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import org.koaks.framework.mcp.entity.McpMessage

interface McpTransport {
    suspend fun <Req, Resp> request(
        method: String,
        params: Req? = null,
        requestSerializer: KSerializer<Req>? = null,
        responseSerializer: KSerializer<Resp>
    ): Resp

    suspend fun <Req> notify(
        method: String,
        params: Req? = null,
        serializer: KSerializer<Req>? = null
    )

    fun messages(): Flow<McpMessage>
}