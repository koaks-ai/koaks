package org.koaks.framework.loop

/**
 * The agent's system instructions, as an ordered list of segments.
 *
 * A segment is either [InstructionSegment.Static] text fixed at build time, or
 * [InstructionSegment.Dynamic] — a `suspend` provider evaluated once per run (when
 * the initial messages are built), so it can read run-time context (clock, user
 * profile, a retrieval call). [resolve] drops blank/`null` segments and joins the
 * rest with a blank line into the single system message the loop prepends.
 *
 * We name this `instructions` (not `systemPrompt`) so the public API expresses
 * intent — "what the agent should do" — and leaves each provider's WireAdapter free
 * to encode it as a system message (OpenAI) or a top-level `system` field (Anthropic).
 */
class Instructions internal constructor(
    private val segments: List<InstructionSegment>,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /** Evaluates every segment and joins the non-blank results; `null` if nothing remains. */
    suspend fun resolve(): String? {
        if (segments.isEmpty()) return null
        val parts = segments.mapNotNull { segment ->
            when (segment) {
                is InstructionSegment.Static -> segment.text
                is InstructionSegment.Dynamic -> segment.provider()
            }?.takeIf { it.isNotBlank() }
        }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    internal fun appendStatic(values: Iterable<String>): Instructions = Instructions(
        segments + values.filter { it.isNotBlank() }.map(InstructionSegment::Static),
    )

    companion object {
        val EMPTY: Instructions = Instructions(emptyList())

        /** A single static segment — backs the `instructions = "..."` shorthand. */
        fun of(text: String): Instructions = Instructions(listOf(InstructionSegment.Static(text)))
    }
}

/** One piece of the system instructions. */
sealed interface InstructionSegment {
    data class Static(val text: String) : InstructionSegment
    class Dynamic(val provider: suspend () -> String?) : InstructionSegment
}

/**
 * DSL scope for the `instructions { }` block:
 *
 * ```
 * instructions {
 *     +"You are a concise assistant."          // static
 *     text("Always answer in Chinese.")        // static (explicit form)
 *     dynamic { "Today is ${today()}" }         // evaluated per run
 *     dynamic { userProfile?.let { "Prefs: $it" } }  // null/blank → skipped
 * }
 * ```
 *
 * Segments are emitted in the order written and joined with a blank line.
 */
@AgentDSL
class InstructionsScope {
    private val segments = mutableListOf<InstructionSegment>()

    /** Appends a static segment via unary plus: `+"..."`. */
    operator fun String.unaryPlus() {
        segments += InstructionSegment.Static(this)
    }

    /** Appends a static segment (explicit form). */
    fun text(value: String) {
        segments += InstructionSegment.Static(value)
    }

    /** Appends a segment resolved at run time; return `null`/blank to omit it. */
    fun dynamic(provider: suspend () -> String?) {
        segments += InstructionSegment.Dynamic(provider)
    }

    internal fun build(): Instructions = Instructions(segments.toList())
}
