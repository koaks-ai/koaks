package org.koaks.framework.model

import org.koaks.framework.tool.ToolSchema

/**
 * The provider-agnostic request handed to [LanguageModel.generate]. Providers
 * translate this into their own wire format via `ChatModel.toWire`.
 *
 * @property messages the full conversation working set for this model call.
 * @property tools tool schemas exposed to the model this call (may be empty).
 * @property params sampling parameters.
 * @property stream whether streaming output is requested. Non-streaming is just
 *   the degenerate case where the resulting [Flow] of [ModelEvent] has few events.
 * @property jsonMode whether to ask the provider for native JSON-constrained output.
 */
data class ChatRequest(
    val messages: List<Message>,
    val tools: List<ToolSchema> = emptyList(),
    val params: GenerationParams = GenerationParams(),
    val stream: Boolean = true,
    val jsonMode: Boolean = false,
)
