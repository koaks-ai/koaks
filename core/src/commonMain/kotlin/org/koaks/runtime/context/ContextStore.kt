package org.koaks.runtime.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.koaks.framework.model.Message
import org.koaks.runtime.acb.RunId

/** The three context tiers, mapped from the plan's global / agent-private / task layers. */
enum class ContextScope { GLOBAL, PRIVATE, TASK }

/**
 * An immutable, content-addressed context block. When [parent] is non-null the block is a
 * **delta**: it stores only [messages] added on top of its parent, so shared history is
 * never duplicated (structural sharing / copy-on-write). Resolving walks the parent chain.
 */
class ContextBlock internal constructor(
    val ref: ContextRef,
    val scope: ContextScope,
    val owner: RunId?,
    val parent: ContextRef?,
    val messages: List<Message>,
)

/** Thrown when an agent tries to read a block it has no permission for. */
class ContextAccessException(val ref: ContextRef, val requester: RunId?) :
    Exception("agent $requester may not read $ref")

/**
 * Content-addressed, copy-on-write context storage — the kernel's "memory manager".
 *
 * - **Content addressing**: identical content (within the same scope/owner/parent) maps to
 *   the same [ContextRef], so [put] deduplicates automatically.
 * - **Copy-on-write / delta**: [delta] layers new messages over a parent without copying
 *   the parent's history; only the delta is stored and only the delta needs to travel.
 * - **Three tiers**: [ContextScope.GLOBAL] readable by all, [ContextScope.PRIVATE] readable
 *   only by its owner, [ContextScope.TASK] shared within a task.
 *
 * Addressing is deterministic within a store instance (FNV-1a over role+text), which is all
 * dedup requires; it is not a cryptographic digest.
 */
class ContextStore {
    private val blocks = MutableStateFlow<Map<String, ContextBlock>>(emptyMap())

    /** Stores a root block and returns its ref (deduplicated by content). */
    fun put(messages: List<Message>, scope: ContextScope = ContextScope.GLOBAL, owner: RunId? = null): ContextRef {
        val ref = ContextRef(address(scope, owner, null, messages))
        blocks.update { if (ref.id in it) it else it + (ref.id to ContextBlock(ref, scope, owner, null, messages)) }
        return ref
    }

    /** Layers [added] over [parent] as a delta block (copy-on-write). */
    fun delta(
        parent: ContextRef,
        added: List<Message>,
        scope: ContextScope = ContextScope.GLOBAL,
        owner: RunId? = null,
    ): ContextRef {
        require(parent.id in blocks.value) { "unknown parent context ref: ${parent.id}" }
        val ref = ContextRef(address(scope, owner, parent, added))
        blocks.update { if (ref.id in it) it else it + (ref.id to ContextBlock(ref, scope, owner, parent, added)) }
        return ref
    }

    /** The raw block for [ref], or `null` if unknown. */
    fun get(ref: ContextRef): ContextBlock? = blocks.value[ref.id]

    /**
     * Resolves [ref] to the full message list, walking the parent chain and enforcing
     * per-block access for [requester]. Throws [ContextAccessException] on denial.
     */
    fun resolve(ref: ContextRef, requester: RunId?): List<Message> {
        val chain = ArrayList<ContextBlock>()
        var cur: ContextRef? = ref
        while (cur != null) {
            val block = blocks.value[cur.id] ?: error("unknown context ref: ${cur.id}")
            checkAccess(block, requester)
            chain.add(block)
            cur = block.parent
        }
        chain.reverse() // root first
        return chain.flatMap { it.messages }
    }

    private fun checkAccess(block: ContextBlock, requester: RunId?) {
        val allowed = when (block.scope) {
            ContextScope.GLOBAL, ContextScope.TASK -> true
            ContextScope.PRIVATE -> requester != null && requester == block.owner
        }
        if (!allowed) throw ContextAccessException(block.ref, requester)
    }

    private fun address(scope: ContextScope, owner: RunId?, parent: ContextRef?, messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append(scope.name).append('|').append(owner?.value ?: -1L).append('|').append(parent?.id ?: "").append('|')
        for (m in messages) sb.append(m.role.name).append(':').append(m.text).append('\n')
        return "blk-" + fnv1a(sb.toString())
    }

    private fun fnv1a(s: String): String {
        var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
        val prime = 1099511628211L
        for (c in s) {
            hash = hash xor c.code.toLong()
            hash *= prime
        }
        // Include length to further reduce accidental collisions.
        return "${hash.toULong().toString(16)}-${s.length}"
    }
}
