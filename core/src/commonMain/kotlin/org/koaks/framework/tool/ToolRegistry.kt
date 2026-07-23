package org.koaks.framework.tool

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.tool.schema.SerialDescriptorToJsonSchema
import org.koaks.framework.utils.json.JsonUtil

/**
 * A scoped tool registry — an instance, NOT a global object. Tool names are
 * unique within this registry only, so different agents never collide globally.
 *
 * Tool sources may be appended while an Agent is being built. Agent preparation
 * resolves all lazy sources, merges Skill tools atomically, and permanently freezes
 * the resulting catalog.
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool<*>>()
    private val lazySources = mutableListOf<LazyToolSource>()
    private val preparationMutex = Mutex()
    private var frozen = false

    /** Registers a tool. Fails fast on a duplicate name within this registry. */
    internal fun register(tool: Tool<*>) {
        check(!frozen) { "tool registry is already prepared" }
        require(tool.name !in tools) { "duplicate tool: ${tool.name}" }
        tools[tool.name] = tool
    }

    /** Registers a batch of tools (used by runtime sources such as MCP discovery). */
    internal fun registerAll(newTools: Iterable<Tool<*>>) {
        check(!frozen) { "tool registry is already prepared" }
        val additions = newTools.toList()
        validateAdditions(additions)
        additions.forEach { tools[it.name] = it }
    }

    /** Registers a deferred tool source, resolved once on the first run. */
    internal fun addLazySource(source: LazyToolSource) {
        check(!frozen) { "tool sources can only be added before preparation" }
        lazySources += source
    }

    /** Resolves lazy sources, atomically merges Skill tools, then freezes this Agent registry. */
    internal suspend fun prepare(additionalTools: Iterable<Tool<*>>) = preparationMutex.withLock {
        if (frozen) return@withLock
        val additions = buildList {
            addAll(discoverLazyToolsForPreparation())
            addAll(additionalTools)
        }
        validatePreparationAdditions(additions)
        additions.forEach { tools[it.name] = it }
        frozen = true
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
    suspend fun call(
        name: String,
        argsJson: String,
        onSideEffect: () -> Unit = {},
    ): ToolOutcome {
        val tool = tools[name] ?: return ToolOutcome.Failure(AgentError.ToolNotFound(name))
        return invoke(tool, argsJson, onSideEffect)
    }

    private suspend fun discoverLazyTools(): List<Tool<*>> = buildList {
        for (source in lazySources) addAll(source.discover())
    }

    private suspend fun discoverLazyToolsForPreparation(): List<Tool<*>> = try {
        discoverLazyTools()
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (failure: AgentFrameworkException) {
        throw failure
    } catch (failure: Throwable) {
        throw preparationFailure(
            message = "lazy tool discovery failed: ${failure.message ?: "unknown error"}",
            cause = failure,
        )
    }

    private fun validatePreparationAdditions(additions: List<Tool<*>>) {
        try {
            validateAdditions(additions)
        } catch (failure: IllegalArgumentException) {
            throw preparationFailure(
                message = failure.message ?: "tool validation failed",
                cause = failure,
            )
        }
    }

    private fun preparationFailure(message: String, cause: Throwable): AgentFrameworkException =
        AgentFrameworkException(
            AgentError.PreparationError(
                component = "tools",
                message = message,
                cause = cause,
            ),
        )

    private fun validateAdditions(additions: List<Tool<*>>) {
        val names = tools.keys.toMutableSet()
        additions.forEach { tool ->
            require(tool.name.isNotBlank()) { "tool name must not be blank" }
            require(names.add(tool.name)) { "duplicate tool: ${tool.name}" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <In> invoke(tool: Tool<In>, argsJson: String, onSideEffect: () -> Unit): ToolOutcome {
        // Passthrough tools (e.g. MCP adapters) receive the raw arguments string directly.
        if (tool.acceptsRawJson) {
            return try {
                if (tool.hasSideEffects) onSideEffect()
                ToolOutcome.Success((tool as Tool<String>).execute(argsJson), tool.returnDirectly)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: AgentFrameworkException) {
                ToolOutcome.Failure(e.error)
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
            if (tool.hasSideEffects) onSideEffect()
            ToolOutcome.Success(tool.execute(input), tool.returnDirectly)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: AgentFrameworkException) {
            ToolOutcome.Failure(e.error)
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
