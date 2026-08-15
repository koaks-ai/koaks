package org.koaks.provider.openai

/**
 * How OpenAI Responses carries conversation state across model calls.
 *
 * - [ServerStored]: `store=true` and `previous_response_id` from a checkpoint.
 * - [Replayable]: `store=false`, `include=reasoning.encrypted_content`, full items round-trip.
 *   Must not send `previous_response_id`.
 * - [Conversation]: stateless full transcript, no server-side response chaining.
 */
enum class ResponsesStateMode {
    ServerStored,
    Replayable,
    Conversation,
}

/** The 22 Responses output / input item types this provider understands. */
object ResponsesItemTypes {
    const val MESSAGE = "message"
    const val FUNCTION_CALL = "function_call"
    const val FUNCTION_CALL_OUTPUT = "function_call_output"
    const val FILE_SEARCH_CALL = "file_search_call"
    const val WEB_SEARCH_CALL = "web_search_call"
    const val COMPUTER_CALL = "computer_call"
    const val COMPUTER_CALL_OUTPUT = "computer_call_output"
    const val REASONING = "reasoning"
    const val IMAGE_GENERATION_CALL = "image_generation_call"
    const val CODE_INTERPRETER_CALL = "code_interpreter_call"
    const val LOCAL_SHELL_CALL = "local_shell_call"
    const val LOCAL_SHELL_CALL_OUTPUT = "local_shell_call_output"
    const val MCP_LIST_TOOLS = "mcp_list_tools"
    const val MCP_APPROVAL_REQUEST = "mcp_approval_request"
    const val MCP_APPROVAL_RESPONSE = "mcp_approval_response"
    const val MCP_CALL = "mcp_call"
    const val CUSTOM_TOOL_CALL = "custom_tool_call"
    const val CUSTOM_TOOL_CALL_OUTPUT = "custom_tool_call_output"
    const val ITEM_REFERENCE = "item_reference"
    const val COMPACTION = "compaction"
    const val TOOL_SEARCH_CALL = "tool_search_call"
    const val APPLY_PATCH_CALL = "apply_patch_call"

    val ALL: Set<String> = setOf(
        MESSAGE, FUNCTION_CALL, FUNCTION_CALL_OUTPUT, FILE_SEARCH_CALL, WEB_SEARCH_CALL,
        COMPUTER_CALL, COMPUTER_CALL_OUTPUT, REASONING, IMAGE_GENERATION_CALL,
        CODE_INTERPRETER_CALL, LOCAL_SHELL_CALL, LOCAL_SHELL_CALL_OUTPUT,
        MCP_LIST_TOOLS, MCP_APPROVAL_REQUEST, MCP_APPROVAL_RESPONSE, MCP_CALL,
        CUSTOM_TOOL_CALL, CUSTOM_TOOL_CALL_OUTPUT, ITEM_REFERENCE, COMPACTION,
        TOOL_SEARCH_CALL, APPLY_PATCH_CALL,
    )
}
