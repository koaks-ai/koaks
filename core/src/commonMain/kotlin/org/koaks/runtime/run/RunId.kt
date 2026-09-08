package org.koaks.runtime.run

import kotlin.jvm.JvmInline

/**
 * A runtime-unique identifier for one execution of an Agent definition.
 * It identifies a *running instance*, not an [org.koaks.framework.loop.Agent] definition
 * (the same immutable agent can be spawned many times, each getting a fresh [RunId]).
 */
@JvmInline
value class RunId(val value: Long) {
    override fun toString(): String = "run#$value"
}
