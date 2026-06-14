package org.koaks.framework.loop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.koaks.framework.tool.InlineTool
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
}

/** Inline tool with reified input type — `tool<MyInput>("name", "desc") { ... }`. */
inline fun <reified In> ToolScope.tool(
    name: String,
    description: String,
    returnDirectly: Boolean = false,
    hasSideEffects: Boolean = false,
    noinline execute: suspend (In) -> String,
) = register(name, description, serializer<In>(), returnDirectly, hasSideEffects, execute)
