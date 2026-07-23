package org.koaks.cli.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.cli.agent.AgentFactory
import org.koaks.cli.config.AgentConfig
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.tool.Tool
import org.koaks.runtime.resource.ChildConversation
import org.koaks.runtime.resource.ChildFailurePolicy
import org.koaks.runtime.resource.spawnChild

@Serializable
internal data class TaskInput(
    /** Short 3–5 word label shown in the CLI tool line (e.g. "Explore auth module"). */
    val description: String,
    /** Full task prompt handed to the sub-agent as its user input. */
    val prompt: String,
    /**
     * Sub-agent profile: `general` (default), `explore` (read-only investigation),
     * or `worker` (focused execution slice for parallel fan-out).
     */
    @SerialName("subagent_type")
    val subagentType: String = SubagentType.GENERAL.id,
)

/**
 * Spawns an isolated child agent, awaits its result, and returns the text to the parent.
 *
 * Multiple Task calls in one model step run in parallel (AgentRunner executes tools
 * concurrently). Children use ephemeral conversations, so parallel execution does not
 * create persistent Thread bindings.
 */
internal class TaskTool private constructor(
    private val subagents: Map<SubagentType, Agent>,
) : Tool<TaskInput> {
    constructor(config: AgentConfig) : this(
        SubagentType.entries.associateWith { type -> AgentFactory.buildSubagent(config, type) },
    )

    internal constructor(type: SubagentType, agent: Agent) : this(mapOf(type to agent))

    override val name: String = "Task"
    override val description: String =
        "Launch a sub-agent to handle a focused subtask, then return its final answer. " +
            "Use for exploration, long investigations, or parallel fan-out (call Task multiple " +
            "times in the same step — they run concurrently). " +
            "`description` is a short label; `prompt` is the full brief for the sub-agent. " +
            "`subagent_type`: general (default, full tools), explore (read-only codebase investigation), " +
            "worker (focused slice for parallel batches). Sub-agents cannot spawn further Tasks."
    override val inputSerializer = TaskInput.serializer()

    override suspend fun execute(input: TaskInput): String {
        val description = input.description.trim()
        val prompt = input.prompt.trim()
        if (description.isEmpty()) failTask("description is required")
        if (prompt.isEmpty()) failTask("prompt is required")

        val type = try {
            SubagentType.parse(input.subagentType)
        } catch (e: IllegalStateException) {
            failTask(e.message ?: "invalid subagent_type", e)
        }

        val child = subagents.getValue(type)
        val result = spawnChild(
            agent = child,
            input = prompt,
            failurePolicy = ChildFailurePolicy.CAPTURE,
            conversation = ChildConversation.Ephemeral,
        ).await()

        return formatResult(description, type, result)
    }

    private fun formatResult(description: String, type: SubagentType, result: AgentResult): String {
        val header = "[subagent ${type.id}] $description"
        return when (result) {
            is AgentResult.Completed -> {
                val body = result.text.trim()
                if (body.isEmpty()) {
                    "$header\n(completed with empty output)"
                } else {
                    truncate("$header\n$body")
                }
            }
            is AgentResult.Terminated -> {
                val body = result.text.trim()
                val details = truncate(
                    buildString {
                        appendLine(header)
                        appendLine("Task terminated: ${result.reason}")
                        if (body.isNotEmpty()) append(body)
                    }.trimEnd(),
                )
                failTask(details)
            }
            is AgentResult.Failed -> throw AgentFrameworkException(result.error)
        }
    }

    private fun failTask(message: String, cause: Throwable? = null): Nothing =
        throw AgentFrameworkException(
            AgentError.ToolError(
                toolName = name,
                message = message,
                retriable = false,
                cause = cause,
            ),
        )

    private fun truncate(text: String): String {
        if (text.length <= MAX_TASK_RESULT_CHARS) return text
        return text.take(MAX_TASK_RESULT_CHARS) +
            "\n[truncated to $MAX_TASK_RESULT_CHARS of ${text.length} characters]"
    }

    private companion object {
        const val MAX_TASK_RESULT_CHARS = 80_000
    }
}
