package org.koaks.framework.loop

import kotlin.jvm.JvmInline

/** Stable identity of an immutable [Agent] definition inside a runtime. */
@JvmInline
value class AgentId(val value: String) {
    init {
        require(value.isNotBlank()) { "AgentId must not be blank" }
    }

    override fun toString(): String = value
}
