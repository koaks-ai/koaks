package org.koaks.framework.model

import org.koaks.framework.skill.SkillId
import org.koaks.framework.skill.SkillStage

/**
 * The framework's first-class error model. Shared across [ModelEvent.Failed],
 * `ToolOutcome.Failure`, and `AgentEvent.Failed`.
 *
 * Error classification directly drives whether recovery can safely retry: network
 * hiccups are retriable, argument parse failures are not. This is why it is a
 * structured hierarchy rather than a bare `Throwable`/`String`. The interface is
 * intentionally extensible so future framework errors do not break downstream code.
 */
interface AgentError {
    val message: String
    val cause: Throwable?

    /**
     * Model / transport failure. [retriable] is true ONLY for failures that occur
     * before the first byte reaches the consumer (connection, auth, first-packet
     * timeout); mid-stream breakage is never retriable.
     */
    data class ModelError(
        override val message: String,
        val retriable: Boolean,
        override val cause: Throwable? = null,
    ) : AgentError

    /** A tool's [execute] threw. Whether it is retriable is decided by tool semantics. */
    data class ToolError(
        val toolName: String,
        override val message: String,
        val retriable: Boolean,
        override val cause: Throwable? = null,
    ) : AgentError

    /** JSON parse failure for tool arguments or structured output. Never retriable. */
    data class ParseError(
        override val message: String,
        val raw: String,
        override val cause: Throwable? = null,
    ) : AgentError

    /** The model asked for a tool that is not registered. Configuration error, not retriable. */
    data class ToolNotFound(val toolName: String) : AgentError {
        override val message: String get() = "tool not found: $toolName"
        override val cause: Throwable? get() = null
    }

    /** Skill discovery, loading, validation, or resource access failed. */
    data class SkillError(
        val skillId: SkillId?,
        val stage: SkillStage,
        override val message: String,
        override val cause: Throwable? = null,
    ) : AgentError

    /** Agent component initialization failed before the first model request. */
    data class PreparationError(
        val component: String,
        override val message: String,
        override val cause: Throwable? = null,
    ) : AgentError

    /** A stage exceeded its time budget. Retriable. */
    data class Timeout(val stage: String, val elapsedMs: Long) : AgentError {
        override val message: String get() = "$stage timed out after ${elapsedMs}ms"
        override val cause: Throwable? get() = null
    }
}
