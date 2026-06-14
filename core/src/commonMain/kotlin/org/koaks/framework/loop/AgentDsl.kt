package org.koaks.framework.loop

/** Restricts the agent DSL scopes so inner lambdas can't accidentally see outer members. */
@DslMarker
annotation class AgentDsl
