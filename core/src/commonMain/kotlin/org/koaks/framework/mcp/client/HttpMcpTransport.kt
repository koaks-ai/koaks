package org.koaks.framework.mcp.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

import org.koaks.framework.mcp.entity.McpMessage
import org.koaks.framework.mcp.entity.RpcEnvelope
import org.koaks.framework.mcp.entity.RpcResponse
import org.koaks.framework.model.TypeAdapter
import org.koaks.framework.net.KtorHttpClient

class HttpMcpTransport(
    private val httpClient: KtorHttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : McpTransport {

    private val logger = KotlinLogging.logger {}

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var counter = 0

    // todo: need make it thread safety
    private fun nextId(): Int =  ++counter

    override suspend fun <Req, Res> request(
        method: String,
        params: Req?,
        requestSerializer: KSerializer<Req>?,
        responseSerializer: KSerializer<Res>
    ): Res {
        val req = RpcEnvelope(
            id = nextId(),
            method = method,
            params = params?.let { requestSerializer?.let { ser -> json.encodeToJsonElement(ser, params) } }
        )

        val adapter = TypeAdapter(RpcEnvelope.serializer(), RpcResponse.serializer(responseSerializer))
        val response = httpClient.postAsObject(req, adapter).getOrElse {
            logger.error(it) { "request failed: $method" }
            throw it
        }

        if (response.error != null) {
            throw RuntimeException("RPC error ${response.error.code}: ${response.error.message}")
        }
        return response.result ?: throw RuntimeException("Empty result for $method")
    }

    override suspend fun <Req> notify(
        method: String,
        params: Req?,
        serializer: KSerializer<Req>?
    ) {
        val req = RpcEnvelope(
            id = null,
            method = method,
            params = params?.let { serializer?.let { ser -> json.encodeToJsonElement(ser, params) } }
        )

        val adapter = TypeAdapter(RpcEnvelope.serializer(), JsonElement.serializer())
        httpClient.postAsObject(req, adapter).onFailure {
            logger.error(it) { "notify failed: $method" }
        }
    }

    override fun messages(): Flow<McpMessage> {
        val req = RpcEnvelope(id = null, method = "events/subscribe")
        val adapter = TypeAdapter(RpcEnvelope.serializer(), McpMessage.serializer())

        return httpClient.postAsObjectStream(req, adapter)
            .onEach { msg ->
                logger.debug { "Received MCP message: $msg" }
            }
            .catch { e ->
                logger.error(e) { "Error in messages() stream" }
                throw e
            }
    }
}