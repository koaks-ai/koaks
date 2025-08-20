package org.koaks.framework.entity.chat.responsesapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.enums.IncludeEnum
import org.koaks.framework.toolcall.ToolDefinition

@Serializable
data class ResponsesRequest(

    /**
     * Whether to run the model response in the background.
     *
     * `optional` default: `false`
     */
    val background: Boolean = false,

    /**
     * Specify additional output data to include in the model response.
     *
     * See [IncludeEnum] for a list of available fields.
     *
     * `optional`
     */
    val include: List<IncludeEnum>? = null,

    // todo: needs improvement
    /**
     * Text, image, or file inputs to the model, used to generate a response.
     *
     * `optional`
     */
    val input: String? = null,

    /**
     * A system (or developer) message inserted into the model's context.
     *
     * When using along with previous_response_id, the instructions from a previous response will not be carried
     * over to the next response. This makes it simple to swap out system (or developer) messages in new responses.
     *
     * `optional`
     */
    val instructions: String? = null,

    /**
     * An upper bound for the number of tokens that can be generated for a response,
     * including visible output tokens and reasoning tokens.
     *
     * `optional`
     */
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,

    /**
     * The maximum number of total calls to built-in tools that can be processed in a response.
     * This maximum number applies across all built-in tool calls, not per individual tool.
     * Any further attempts to call a tool by the model will be ignored.
     *
     * `optional`
     */
    @SerialName("max_tool_calls")
    val maxToolCalls: Int? = null,


    /**
     * Set of 16 key-value pairs that can be attached to an object.
     * This can be useful for storing additional information about the object in a structured format,
     * and querying for objects via API or the dashboard.
     *
     * Keys are strings with a maximum length of 64 characters. Values are strings with a maximum length of 512 characters.
     *
     * `optional`
     */
    val metadata: Map<String, String>? = null,

    /**
     * Model ID used to generate the response, like gpt-4o or o3.
     *
     * `optional`
     */
    @SerialName("model")
    val model: String? = null,

    /**
     * Whether to allow the model to run tool calls in parallel.
     *
     * `optional` default: `true`
     */
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean = true,

    /**
     * The unique ID of the previous response to the model. Use this to create multi-turn conversations.
     *
     * `optional`
     */
    @SerialName("previous_response_id")
    val previousResponseId: String? = null,

    /**
     * Reference to a prompt template and its variables.
     *
     * `optional`
     */
    val prompt: PromptConfig? = null,

    /**
     * Used by OpenAI to cache responses for similar requests to optimize your cache hit rates.
     * Replaces the user field.
     *
     * `optional`
     */
    @SerialName("prompt_cache_key")
    val promptCacheKey: String? = null,

    /**
     * Configuration options for reasoning models.
     *
     * `gpt-5 and o-series models only.`
     *
     * `optional`
     */
    val reasoning: ReasoningConfig? = null,

    /**
     * A stable identifier used to help detect users of your application that may be violating OpenAI's usage policies.
     * The IDs should be a string that uniquely identifies each user.
     * We recommend hashing their username or email address, in order to avoid sending us any identifying information.
     *
     * `optional`
     */
    @SerialName("safety_identifier")
    val safetyIdentifier: String? = null,

    /**
     * Specifies the processing type used for serving the request.
     *
     * `default`: The request will be processed with the standard pricing and performance for the selected model.
     *
     * `flex` or `priority`: The request will be processed with the corresponding service tier.
     *
     * When the service_tier parameter is set, the response body will include the service_tier value
     * based on the processing mode actually used to serve the request.
     * This response value may be different from the value set in the parameter.
     *
     * `optional`
     */
    @SerialName("service_tier")
    val serviceTier: String = "default",

    /**
     * Whether to store the generated model response for later retrieval via API.
     *
     * `optional` default: `true`
     */
    val store: Boolean = true,

    /**
     * If set to true, the model response data will be streamed to the client as it is generated using server-sent events.
     *
     * `optional` default: `false`
     */
    val stream: Boolean = false,

    /**
     * Options for streaming responses. Only set this when you set stream: true.
     *
     * `optional`
     */
    @SerialName("stream_options")
    val streamOptions: StreamOptions? = null,

    /**
     * What sampling temperature to use, between `0` and `2`.
     * Higher values like 0.8 will make the output more random, while lower values like 0.2 will make it more focused
     * and deterministic. We generally recommend altering this or `top_p` but not both.
     *
     * `optional` default: `1.0`
     */
    val temperature: Double = 1.0,

    /**
     * Configuration options for a text response from the model. Can be plain text or structured JSON data.
     *
     * `optional`
     */
    val text: TextConfig? = null,

    /**
     * How the model should select which tool (or tools) to use when generating a response.
     * See the tools parameter to see how to specify which tools the model can call.
     *
     * `optional`
     */
    @SerialName("tool_choice")
    val toolChoice: ToolChoice? = null,

    /**
     * An array of tools the model may call while generating a response.
     * You can specify which tool to use by setting the `tool_choice` parameter.
     *
     * `optional`
     */
    val tools: List<ToolDefinition>? = null,

    /**
     * An integer between `0 and 20` specifying the number of most likely tokens to return at each token position,
     * each with an associated log probability.
     *
     * `optional`
     */
    @SerialName("top_logprobs")
    val topLogprobs: Int? = null,

    /**
     * An alternative to sampling with temperature, called nucleus sampling,
     * where the model considers the results of the tokens with top_p probability mass.
     * So 0.1 means only the tokens comprising the top 10% probability mass are considered.
     *
     * `recommend altering this or temperature but not both.`
     *
     * `optional` default: `1.0`
     */
    @SerialName("top_p")
    val topP: Double = 1.0,

    /**
     * The truncation strategy to use for the model response.
     *
     * `auto`: If the context of this response and previous ones exceeds the model's context window size,
     * the model will truncate the response to fit the context window by dropping input items in the middle of the conversation.
     *
     * `disabled`: If a model response will exceed the context window size for a model, the request will fail with a 400 error.
     *
     * `optional` default: `disabled`
     */
    @SerialName("truncation")
    val truncation: String = "disabled",

    @Deprecated("This field is being replaced by safety_identifier and prompt_cache_key.")
    val user: String? = null
)

sealed class InputItem {
    data class TextInput(
        val type: String = "text",
        val content: String
    ) : InputItem()

    data class ImageInput(
        val type: String = "image",

        @SerialName("image_url")
        val imageUrl: String
    ) : InputItem()

    data class FileInput(
        val type: String = "file",

        @SerialName("file_id")
        val fileId: String
    ) : InputItem()
}

@Serializable
data class PromptConfig(
    /**
     * The unique identifier of the prompt template to use.
     *
     * `required`
     */
    val id: String? = null,

    /**
     * Optional map of values to substitute in for variables in your prompt.
     * The substitution values can either be strings, or other Response input types like images or files.
     *
     * `optional`
     */
    val variables: Map<String, String>? = null,

    /**
     * Optional version of the prompt template.
     *
     * `optional`
     */
    val version: String? = null
)

@Serializable
data class ReasoningConfig(
    /**
     * Constrains effort on reasoning for reasoning models.
     * Currently supported values are `minimal`, `low`, `medium`, and `high`.
     * Reducing reasoning effort can result in faster responses and fewer tokens used on reasoning in a response.
     *
     * `optional` default: `medium`
     */
    val effort: String = "medium",

    /**
     * A summary of the reasoning performed by the model.
     * This can be useful for debugging and understanding the model's reasoning process.
     * One of `auto`, `concise`, or `detailed`.
     *
     * `optional`
     */
    @SerialName("summary")
    val summary: String? = null
)

@Serializable
data class StreamOptions(
    /**
     * When true, stream obfuscation will be enabled.
     * Stream obfuscation adds random characters to an obfuscation field on streaming delta events to
     * normalize payload sizes as a mitigation to certain side-channel attacks.
     * These obfuscation fields are included by default, but add a small amount of overhead to the data stream.
     * You can set include_obfuscation to `false` to optimize for bandwidth if you trust the network links between
     * your application and the OpenAI API.
     *
     * `optional`
     */
    @SerialName("include_usage")
    val includeUsage: Boolean? = null
)

@Serializable
data class TextConfig(
    // TODO: add support for text config
    /**
     * An object specifying the format that the model must output.
     *
     * Configuring { "type": "json_schema" } enables Structured Outputs, which ensures the model will match your supplied JSON schema.
     *
     * `optional` default: `{ "type": "text" }`
     */
    @Deprecated("Not recommended for gpt-4o and newer models")
    val format: String? = null,

    /**
     * Constrains the verbosity of the model's response. Lower values will result in more concise responses,
     * while higher values will result in more verbose responses. Currently supported values are `low`, `medium`, and `high`.
     *
     * `optional` default: `medium`
     */
    val verbosity: String = "medium"
)

@Serializable
data class ToolChoice(

    // todo： Actually, I didn't quite figure out what OpenAI's documentation is trying to convey here.
    //  Let's set this field aside for now, even though it's important.
    /**
     * Controls which (if any) tool is called by the model.
     *
     * `none` means the model will not call any tool and instead generates a message.
     *
     * `auto` means the model can pick between generating a message or calling one or more tools.
     *
     * `required` means the model must call one or more tools.
     */
    @SerialName("tool_choice_mode")
    val toolChoiceModeL: String? = null
)
