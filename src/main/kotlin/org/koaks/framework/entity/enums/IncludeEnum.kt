package org.koaks.framework.entity.enums
import com.google.gson.annotations.SerializedName

enum class IncludeEnum(
    val field: String,
    val description: String
) {
    @SerializedName("code_interpreter_call.outputs")
    CODE_INTERPRETER_OUTPUTS(
        "code_interpreter_call.outputs",
        "Includes the outputs of python code execution in code interpreter tool call items."
    ),

    @SerializedName("computer_call_output.output.image_url")
    COMPUTER_CALL_IMAGE_URL(
        "computer_call_output.output.image_url",
        "Include image urls from the computer call output."
    ),

    @SerializedName("file_search_call.results")
    FILE_SEARCH_RESULTS(
        "file_search_call.results",
        "Include the search results of the file search tool call."
    ),

    @SerializedName("message.input_image.image_url")
    MESSAGE_INPUT_IMAGE_URL(
        "message.input_image.image_url",
        "Include image urls from the input message."
    ),

    @SerializedName("message.output_text.logprobs")
    MESSAGE_OUTPUT_LOGPROBS(
        "message.output_text.logprobs",
        "Include logprobs with assistant messages."
    ),

    @SerializedName("reasoning.encrypted_content")
    REASONING_ENCRYPTED_CONTENT(
        "reasoning.encrypted_content",
        "Includes an encrypted version of reasoning tokens in reasoning item outputs. " +
                "This enables reasoning items to be used in multi-turn conversations when using the Responses API statelessly " +
                "(like when the store parameter is set to false, or when an organization is enrolled in the zero data retention program)."
    );

    companion object {
        fun fromField(field: String): IncludeEnum? {
            return entries.firstOrNull { it.field == field }
        }
    }
}
