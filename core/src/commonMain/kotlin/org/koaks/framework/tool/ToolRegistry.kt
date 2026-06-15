package org.koaks.framework.tool

import org.koaks.framework.model.AgentError
import org.koaks.framework.tool.schema.SerialDescriptorToJsonSchema
import org.koaks.framework.utils.json.JsonUtil

/**
 * A scoped tool registry — an instance, NOT a global object. Tool names are
 * unique within this registry only, so different agents never collide globally.
 *
 * Supports runtime appension (for later MCP lazy discovery) rather than
 * being fixed at construction.
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool<*>>()
    private val lazySources = mutableListOf<LazyToolSource>()
    private var sourcesResolved = false

    /** Registers a tool. Fails fast on a duplicate name within this registry. */
    fun register(tool: Tool<*>) {
        require(tool.name !in tools) { "duplicate tool: ${tool.name}" }
        tools[tool.name] = tool
    }

    /** Registers a batch of tools (used by runtime sources such as MCP discovery). */
    fun registerAll(newTools: Iterable<Tool<*>>) = newTools.forEach { register(it) }

    /** Registers a deferred tool source, resolved once on the first run. */
    fun addLazySource(source: LazyToolSource) {
        lazySources += source
    }

    /**
     * Resolves any pending [LazyToolSource]s exactly once and appends the discovered
     * tools. Idempotent — subsequent calls are no-ops. Called by [AgentRunner] in a
     * suspend context before the first model step.
     */
    suspend fun resolveLazySources() {
        if (sourcesResolved || lazySources.isEmpty()) {
            sourcesResolved = true
            return
        }
        sourcesResolved = true
        for (source in lazySources) registerAll(source.discover())
    }

    fun isEmpty(): Boolean = tools.isEmpty()

    fun names(): Set<String> = tools.keys.toSet()

    /** True if any registered tool declares external side effects. */
    fun hasSideEffectingTools(): Boolean = tools.values.any { it.hasSideEffects }

    /** Produces the JSON-schema descriptions handed to the model. */
    fun toSchemas(): List<ToolSchema> = tools.values.map { tool ->
        ToolSchema(
            name = tool.name,
            description = tool.description,
            parameters = tool.parametersOverride
                ?: SerialDescriptorToJsonSchema.generate(tool.inputSerializer.descriptor),
        )
    }

    /**
     * Invokes a tool by name with raw JSON arguments. A missing tool returns
     * [ToolOutcome.Failure] with [AgentError.ToolNotFound] — it never fabricates a
     * result. Argument-decode failures become [AgentError.ParseError]; thrown
     * exceptions become [AgentError.ToolError].
     */
    suspend fun call(name: String, argsJson: String): ToolOutcome {
        val tool = tools[name] ?: return ToolOutcome.Failure(AgentError.ToolNotFound(name))
        return invoke(tool, argsJson)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <In> invoke(tool: Tool<In>, argsJson: String): ToolOutcome {
        // Passthrough tools (e.g. MCP adapters) receive the raw arguments string directly.
        if (tool.acceptsRawJson) {
            return try {
                ToolOutcome.Success((tool as Tool<String>).execute(argsJson), tool.returnDirectly)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome.Failure(
                    AgentError.ToolError(
                        toolName = tool.name,
                        message = e.message ?: "tool '${tool.name}' execution failed",
                        retriable = false,
                        cause = e,
                    )
                )
            }
        }

        val input: In = try {
            val raw = if (argsJson.isBlank()) "{}" else argsJson
            JsonUtil.fromJson(raw, tool.inputSerializer)
        } catch (e: Exception) {
            return ToolOutcome.Failure(
                AgentError.ParseError(
                    message = "failed to decode arguments for tool '${tool.name}': ${e.message}",
                    raw = argsJson,
                    cause = e,
                )
            )
        }

        return try {
            ToolOutcome.Success(tool.execute(input), tool.returnDirectly)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.Failure(
                AgentError.ToolError(
                    toolName = tool.name,
                    message = e.message ?: "tool '${tool.name}' execution failed",
                    retriable = false,
                    cause = e,
                )
            )
        }
    }
}
