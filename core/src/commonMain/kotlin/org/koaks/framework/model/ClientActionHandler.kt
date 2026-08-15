package org.koaks.framework.model

/**
 * Executes provider-specific client actions (computer use, MCP approval, local shell)
 * without the agent loop hard-coding provider types. Unhandled actions stay in the
 * transcript as [ModelItem.ProviderItem]s.
 */
fun interface ClientActionHandler {
    suspend fun handle(item: ModelItem.ProviderItem): ModelItem?
}
