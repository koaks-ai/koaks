package org.koaks.runtime.acb

import kotlin.jvm.JvmInline

/** Runtime-unique identity of one atomic conversation turn. */
@JvmInline
value class TurnId(val value: Long) {
    override fun toString(): String = "turn#$value"
}
