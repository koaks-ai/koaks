package org.koaks.framework.mcp

import kotlinx.serialization.json.JsonObject
import org.koaks.framework.mcp.entity.McpTool
import org.koaks.framework.tool.LazyToolSource
import org.koaks.framework.tool.Tool

/**
 * The minimal capability the agent needs from an MCP server: list its tools and
 * invoke one by name with raw JSON arguments. Abstracted as an interface so it can
 * be backed by [org.koaks.framework.mcp.client.DefaultMcpClient] in production or a
 * fake in tests.
 */
interface McpToolGateway {
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, argumentsJson: String): String
}

/**
 * A [LazyToolSource] that discovers an MCP server's tools at run time (§5.1). The
 * synchronous `agent { }` builder records this source; [org.koaks.framework.loop.AgentRunner]
 * resolves it once on the first run inside a suspend context.
 */
class McpToolSource(private val gateway: McpToolGateway) : LazyToolSource {
    override suspend fun discover(): List<Tool<*>> =
        gateway.listTools().map { McpToolAdapter(it, gateway) }
}

/**
 * Adapts an MCP-advertised tool into the framework's [Tool]. The MCP server owns the
 * input schema, so it is passed through verbatim via [parametersOverride] rather than
 * derived from a serializer; arguments stay as the raw JSON string the model emitted.
 */
internal class McpToolAdapter(
    private val mcpTool: McpTool,
    private val gateway: McpToolGateway,
) : Tool<String> {
    override val name: String get() = mcpTool.name
    override val description: String get() = mcpTool.description
    override val inputSerializer = kotlinx.serialization.serializer<String>()
    override val acceptsRawJson: Boolean get() = true
    override val parametersOverride: JsonObject?
        get() = mcpTool.inputSchema ?: JsonObject(emptyMap())

    override suspend fun execute(input: String): String = gateway.callTool(name, input)
}
