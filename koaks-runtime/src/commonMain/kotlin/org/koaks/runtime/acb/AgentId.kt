package org.koaks.runtime.acb

import kotlin.jvm.JvmInline

/**
 * A runtime-unique identifier for one agent run instance — the analogue of an OS pid.
 * It identifies a *running instance*, not an [org.koaks.framework.loop.Agent] definition
 * (the same immutable agent can be spawned many times, each getting a fresh [AgentId]).
 */
@JvmInline
value class AgentId(val value: Long) {
    override fun toString(): String = "agent#$value"
}
