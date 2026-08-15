package org.koaks.framework.model

import org.koaks.framework.tool.ToolSchema

/**
 * Provider-agnostic request handed to [LanguageModel.stream].
 *
 * [instructions] is already resolved (Static/Dynamic collapsed). Providers encode it
 * as a top-level `system` / `instructions` field. [items] is the conversation working
 * set including the new user turn; when [checkpoint] is valid the provider may send
 * only the suffix after [ProviderCheckpoint.basis].
 *
 * [idempotencyKey] is generated once per logical model step and reused across
 * transport retries and [org.koaks.framework.policy.Recovery.Retry].
 */
data class ModelRequest(
    val instructions: String?,
    val items: List<ModelItem>,
    val tools: List<ToolSchema> = emptyList(),
    val outputFormat: OutputFormat = OutputFormat.Text,
    val checkpoint: ProviderCheckpoint? = null,
    val idempotencyKey: String,
)
