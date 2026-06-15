package org.koaks.framework.tool

/**
 * A deferred source of tools resolved at run time rather than at `agent { }` build
 * time. The DSL is synchronous, but some tool sets (notably MCP) need a suspend
 * handshake + `tools/list` to discover. The builder registers a source;
 * [org.koaks.framework.loop.AgentRunner] resolves it once on the first run and appends the results to the
 * [ToolRegistry] (discover-once, cache-and-reuse).
 */
fun interface LazyToolSource {
    /** Discovers the tools provided by this source. Called once, in a suspend context. */
    suspend fun discover(): List<Tool<*>>
}
