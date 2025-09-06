package org.koaks.framework.toolcall.toolinterface

import kotlinx.serialization.KSerializer
import org.koaks.framework.utils.json.JsonUtil

/**
 * Represents a **callable Tool** in the framework.
 *
 * Tools are units of functionality that can be invoked by an agent (e.g. an LLM).
 *
 * A `Tool<T>` has:
 * - A unique [name], used as the identifier for the tool.
 * - A natural language [description], used by the LLM to decide whether this tool should be used.
 * - An optional [group], useful for categorization / scoping.
 * - A [serializer], used to transform between serialized (JSON) and structured type [T].
 * - An [execute] method, which performs the actual logic with structured input [T].
 *
 * Tools can also be executed directly from JSON input via [executeJson],
 * which will internally deserialize into [T] using [serializer].
 *
 * @param T The structured input type that this tool expects.
 */
interface Tool<T> {
    /**
     * "tool name" is a `unique identifier` for a tool and must not be repeated.
     *
     * `required`
     */
    val name: String

    /**
     * "description" is used to describe the function of the tool,
     * and the llm uses it to determine whether the tool should be used.
     *
     * you should use **Natural language description** describe the function of the tool as clearly as possible,
     * as it plays a crucial role in the accuracy of tool invocation by the llm.
     *
     * `required`
     */
    val description: String

    /**
     * "group" is the grouping information of the tool. It is optional, but it is recommended to set this attribute,
     * because in this way you can put all tools directly into the client and then set the group() information.
     * The framework will select tools based on the group, which can save users from the trouble of manually managing tools.
     *
     * `optional`
     */
    val group: String?

    /**
     * **Serializer for the tool’s input type [T]**.
     *
     * - Responsible for converting JSON ↔ structured input.
     * - Used internally by [executeJson].
     * - Implementations are typically provided automatically if [T] is annotated with `@Serializable`.
     */
    val serializer: KSerializer<T>

    /**
     * By default, the results of tool calls are sent back to the model as responses. The model can then use these results to continue the conversation.
     *
     * In certain scenarios, you may want to return the results directly to the caller instead of sending them back to the model.
     *
     * For example, if you're building an agent that relies on a RAG (Retrieval-Augmented Generation) tool,
     * you may prefer to return the results directly to the caller rather than sending them back to the model
     * for unnecessary post-processing. Alternatively, you may have certain tools that should terminate the agent's reasoning loop.
     */
    val returnDirectly: Boolean

    /**
     * "execute" is the function that actually executes the tool.
     */
    suspend fun execute(input: T): String?

    /**
     * Executes the tool directly from a **JSON input string**.
     *
     * - Internally uses [serializer] + [JsonUtil] to deserialize [json] into [T].
     * - Then delegates to [execute].
     *
     * Use case:
     * - When called by an agent/LLM, the model returns arguments in JSON form.
     *   This method serves as the bridge to structured execution.
     *
     * Can be treated as a convenience function to hide serialization details.
     *
     *
     * `you should never rewrite it at any time`
     */
    suspend fun executeJson(json: String?): String? {
        val input: T = if (json.isNullOrBlank() || json == "{}") {
            if (serializer.descriptor.serialName == "org.koaks.framework.toolcall.toolinterface.NoInput") {
                @Suppress("UNCHECKED_CAST")
                NoInput as T
            } else {
                JsonUtil.fromJson("{}", serializer)
            }
        } else {
            JsonUtil.fromJson(json, serializer)
        }
        return execute(input)
    }

}
