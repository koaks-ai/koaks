package org.koaks.framework.entity

import kotlinx.serialization.Serializable
import org.koaks.framework.toolcall.ToolDefinition

@Serializable
open class ModelParams {

    /** Tool definitions */
    var tools: MutableList<ToolDefinition>? = null

    /** Optional: whether to allow parallel tool calls */
    var parallelToolCalls: Boolean? = null

    /** The default system message for the assistant */
    var systemMessage: String? = null

    /** Optional: maximum number of tokens to generate */
    var maxTokens: Int? = null

    /** Optional: controls randomness of generation; 0 means fully deterministic */
    var temperature: Double? = null

    /** Optional: nucleus sampling parameter, usually between 0 and 1 */
    var topP: Double? = null

    /** Optional: number of candidate results to return per request */
    var n: Int? = null

    /** Optional: whether to return results in streaming mode */
    var stream: Boolean? = null

    /** Optional: whether to enable MCP support */
    var useMcp: Boolean? = null

    /** Optional: stop sequence to terminate generation, can be null */
    var stop: String? = null

    /** Optional: penalty for repeated content in generation, default is 0 */
    var presencePenalty: Double? = null

    /** Optional: penalty for reusing the same token in generation, default is 0 */
    var frequencyPenalty: Double? = null

    /** Optional: modifies the likelihood of specified tokens appearing in the completion */
    var logitBias: String? = null

    /** Optional: response format; for JSON enforce {"type": "json_object"} */
    var responseFormat: Map<String, String>? = null
}