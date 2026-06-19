package org.koaks.framework.middleware

import org.koaks.framework.model.ToolCall

/** Decision returned by [Hook.onToolCall]. */
sealed interface ToolDecision {
    data object Proceed : ToolDecision
    data class ProceedWith(val call: ToolCall) : ToolDecision
    data class Deny(val reason: String) : ToolDecision
}
