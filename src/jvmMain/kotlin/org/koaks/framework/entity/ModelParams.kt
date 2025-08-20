package org.koaks.framework.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.toolcall.ToolDefinition

@Serializable
open class ModelParams {

    // 工具
    var tools: List<ToolDefinition>? = null

    // 可选参数：是否允许并行调用工具
    @SerialName("parallel_tool_calls")
    var parallelToolCalls: Boolean? = null

    var systemMessage: String? = null

    // 可选参数：生成文本的最大 token 数量
    @SerialName("max_tokens")
    var maxTokens: Int? = null

    // 可选参数：控制生成文本的随机性，0 表示完全确定性
    var temperature: Double? = null

    // 可选参数： nucleus sampling 参数，通常取值 0-1
    @SerialName("top_p")
    var topP: Double? = null

    // 可选参数：一次请求返回多少个候选结果
    var n: Int? = null

    // 可选参数：是否以流式返回结果
    var stream: Boolean? = null

    // 可选参数：是否启用 MCP 支持
    var useMcp: Boolean? = null

    // 可选参数：停止生成的标识符，可以为 null
    var stop: String? = null

    // 可选参数：生成时对话重复内容的惩罚，默认 0
    @SerialName("presence_penalty")
    var presencePenalty: Double? = null

    // 可选参数：生成时对话重复使用同一 token 的惩罚，默认 0
    @SerialName("frequency_penalty")
    var frequencyPenalty: Double? = null

    // 可选参数：修改指定标记出现在完成中的可能性
    @SerialName("logit_bias")
    var logitBias: String? = null

    // 可选参数：返回结果的格式,强制Json请设置 {"type": "json_object"}
    @SerialName("response_format")
    var responseFormat: Map<String, String>? = null

}