package org.endow.framework.entity

import com.google.gson.annotations.SerializedName
import org.endow.framework.toolcall.ToolDefinition

open class DefaultRequest {
    // 用户id
    var user: String? = null

    // 模型名称
    var model: String? = null

    // 工具
    private var tools: MutableList<ToolDefinition?>? = null

    // 聊天消息列表
    private var messages: MutableList<Message?>? = null

    // 可选参数：生成文本的最大 token 数量
    @SerializedName("max_tokens")
    var maxTokens: Int? = null

    // 可选参数：控制生成文本的随机性，0 表示完全确定性
    var temperature: Double? = null

    // 可选参数： nucleus sampling 参数，通常取值 0-1
    @SerializedName("top_p")
    var topP: Double? = null

    // 可选参数：一次请求返回多少个候选结果
    var n: Int? = null

    // 可选参数：是否以流式返回结果
    var stream: Boolean? = null

    // 可选参数：停止生成的标识符，可以为 null
    var stop: String? = null

    // 可选参数：生成时对话重复内容的惩罚，默认 0
    @SerializedName("presence_penalty")
    var presencePenalty: Double? = null

    // 可选参数：生成时对话重复使用同一 token 的惩罚，默认 0
    @SerializedName("frequency_penalty")
    var frequencyPenalty: Double? = null

    // 可选参数：修改指定标记出现在完成中的可能性
    @SerializedName("logit_bias")
    var logitBias: String? = null

    constructor(
        model: String?,
        user: String?,
        messages: MutableList<Message?>?,
        tools: MutableList<ToolDefinition?>?,
        maxTokens: Int?,
        temperature: Double?,
        topP: Double?,
        n: Int?,
        stream: Boolean?,
        stop: String?,
        presencePenalty: Double?,
        frequencyPenalty: Double?,
        logitBias: String?
    ) {
        this.model = model
        this.user = user
        this.messages = messages
        this.tools = tools
        this.maxTokens = maxTokens
        this.temperature = temperature
        this.topP = topP
        this.n = n
        this.stream = stream
        this.stop = stop
        this.presencePenalty = presencePenalty
        this.frequencyPenalty = frequencyPenalty
        this.logitBias = logitBias
    }

    fun getMessages(): MutableList<Message?>? {
        return messages
    }

    fun setMessages(messages: MutableList<Message?>?) {
        this.messages = messages
    }

    fun getTools(): MutableList<ToolDefinition?>? {
        return tools
    }

    fun setTools(tools: MutableList<ToolDefinition?>?) {
        this.tools = tools
    }

    override fun toString(): String {
        return "ChatGPTRequest {" +
                "model='" + model + '\'' +
                ", messages=" + messages +
                ", tools=" + tools +
                ", user='" + user + '\'' +
                ", maxTokens=" + maxTokens +
                ", temperature=" + temperature +
                ", topP=" + topP +
                ", n=" + n +
                ", stream=" + this.stream +
                ", stop='" + stop + '\'' +
                ", presencePenalty=" + presencePenalty +
                ", frequencyPenalty=" + frequencyPenalty +
                ", logitBias='" + logitBias + '\'' +
                '}'
    }

    class Builder {
        private var model: String? = null
        private var user: String? = null
        private var messages: MutableList<Message?>? = null
        private var tools: MutableList<ToolDefinition?>? = null
        private var maxTokens: Int? = null
        private var temperature: Double? = null
        private var topP: Double? = null
        private var n: Int? = null
        private var stream: Boolean? = null
        private var stop: String? = null
        private var presencePenalty: Double? = null
        private var frequencyPenalty: Double? = null
        private var logitBias: String? = null

        fun model(model: String?): Builder {
            this.model = model
            return this
        }

        fun user(user: String?): Builder {
            this.user = user
            return this
        }

        fun messages(messages: MutableList<Message?>?): Builder {
            this.messages = messages
            return this
        }

        fun tools(tools: MutableList<ToolDefinition?>?): Builder {
            this.tools = tools
            return this
        }

        fun maxTokens(maxTokens: Int?): Builder {
            this.maxTokens = maxTokens
            return this
        }

        fun temperature(temperature: Double?): Builder {
            this.temperature = temperature
            return this
        }

        fun topP(topP: Double?): Builder {
            this.topP = topP
            return this
        }

        fun n(n: Int?): Builder {
            this.n = n
            return this
        }

        fun stream(stream: Boolean?): Builder {
            this.stream = stream
            return this
        }

        fun stop(stop: String?): Builder {
            this.stop = stop
            return this
        }

        fun presencePenalty(presencePenalty: Double?): Builder {
            this.presencePenalty = presencePenalty
            return this
        }

        fun frequencyPenalty(frequencyPenalty: Double?): Builder {
            this.frequencyPenalty = frequencyPenalty
            return this
        }

        fun logitBias(logitBias: String?): Builder {
            this.logitBias = logitBias
            return this
        }

        fun build(): DefaultRequest {
            return DefaultRequest(
                model, user, messages, tools, maxTokens, temperature, topP, n, stream, stop,
                presencePenalty, frequencyPenalty, logitBias
            )
        }
    }

    companion object {
        fun builder(): Builder {
            return Builder()
        }
    }
}