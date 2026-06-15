package org.koaks.framework.loop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.koaks.framework.mcp.McpToolGateway
import org.koaks.framework.mcp.McpToolSource
import org.koaks.framework.tool.InlineTool
import org.koaks.framework.tool.LazyToolSource
import org.koaks.framework.tool.Tool
import org.koaks.framework.tool.ToolRegistry

/** DSL scope for registering tools into a scoped [ToolRegistry]. */
@AgentDsl
class ToolScope(@PublishedApi internal val registry: ToolRegistry) {

    /** Registers a class-based tool. */
    fun tool(tool: Tool<*>) {
        registry.register(tool)
    }

    /** Registers an inline tool whose input schema is inferred from [In]. */
    fun <In> register(
        name: String,
        description: String,
        serializer: KSerializer<In>,
        returnDirectly: Boolean = false,
        hasSideEffects: Boolean = false,
        execute: suspend (In) -> String,
    ) {
        registry.register(InlineTool(name, description, serializer, returnDirectly, hasSideEffects, execute))
    }

    /** Adds a deferred tool source resolved on the first run (e.g. MCP discovery, §5.1). */
    fun source(source: LazyToolSource) {
        registry.addLazySource(source)
    }

    /**
     * Connects an MCP server by [gateway]; its tools are discovered lazily on the
     * first run via `tools/list` and appended to the registry (§5.1). Discovery is
     * deferred because it requires a suspend handshake the synchronous DSL can't await.
     */
    fun mcp(gateway: McpToolGateway) {
        registry.addLazySource(McpToolSource(gateway))
    }
}

/** Inline tool with reified input type — `tool<MyInput>("name", "desc") { ... }`. */
inline fun <reified In> ToolScope.tool(
    name: String,
    description: String,
    returnDirectly: Boolean = false,
    hasSideEffects: Boolean = false,
    noinline execute: suspend (In) -> String,
) = register(name, description, serializer<In>(), returnDirectly, hasSideEffects, execute)
