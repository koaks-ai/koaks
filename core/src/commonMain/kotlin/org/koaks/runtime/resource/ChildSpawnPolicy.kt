package org.koaks.runtime.resource

import org.koaks.framework.memory.ThreadId

/** Controls how a child run's failure affects its parent. */
enum class ChildFailurePolicy {
    /** A failed or independently-cancelled child fails the parent after the child tree settles. */
    PROPAGATE,

    /** The caller consumes the child's [org.koaks.framework.loop.AgentResult] explicitly. */
    CAPTURE,
}

/** Selects the conversation / memory binding used by a child run. */
sealed interface ChildConversation {
    /** Join the parent's active Turn and share its history snapshot and atomic commit. */
    data object Inherit : ChildConversation

    /** Run without a Thread binding or persistent memory. */
    data object Ephemeral : ChildConversation

    /** Start an independent queued Turn on [id]. */
    data class Thread(val id: ThreadId) : ChildConversation
}
