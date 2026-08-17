package org.koaks.java

import java.time.Duration
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.EventDetail
import org.koaks.runtime.resource.Quota

/** Per-run Java options without exposing Kotlin value classes or default arguments. */
class RunOptions private constructor(
    val threadId: String?,
    val priority: Int,
    val maxSteps: Int?,
    val maxToolCalls: Int?,
    val wallClockTimeout: Duration?,
    val eventDetail: EventDetail,
) {
    internal fun coreThreadId(): ThreadId? = threadId?.let(::ThreadId)

    internal fun quotaOrNull(): Quota? =
        if (maxSteps == null && maxToolCalls == null && wallClockTimeout == null) null
        else Quota(maxSteps, maxToolCalls, wallClockTimeout?.toMillis())

    class Builder private constructor() {
        private var threadId: String? = null
        private var priority: Int = 0
        private var maxSteps: Int? = null
        private var maxToolCalls: Int? = null
        private var wallClockTimeout: Duration? = null
        private var eventDetail: EventDetail = EventDetail.SEMANTIC

        fun threadId(threadId: String?): Builder = apply {
            require(threadId == null || threadId.isNotBlank()) { "thread id must not be blank" }
            this.threadId = threadId
        }

        fun priority(priority: Int): Builder = apply { this.priority = priority }

        fun maxSteps(maxSteps: Int): Builder = apply {
            require(maxSteps > 0) { "maxSteps must be positive" }
            this.maxSteps = maxSteps
        }

        fun maxToolCalls(maxToolCalls: Int): Builder = apply {
            require(maxToolCalls > 0) { "maxToolCalls must be positive" }
            this.maxToolCalls = maxToolCalls
        }

        fun wallClockTimeout(timeout: Duration): Builder = apply {
            require(!timeout.isZero && !timeout.isNegative) { "wallClockTimeout must be positive" }
            this.wallClockTimeout = timeout
        }

        fun eventDetail(eventDetail: EventDetail): Builder = apply { this.eventDetail = eventDetail }

        fun build(): RunOptions = RunOptions(threadId, priority, maxSteps, maxToolCalls, wallClockTimeout, eventDetail)

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    companion object {
        private val DEFAULT = RunOptions(null, 0, null, null, null, EventDetail.SEMANTIC)

        @JvmStatic
        fun builder(): Builder = Builder.create()

        @JvmStatic
        fun defaults(): RunOptions = DEFAULT
    }
}
