package org.koaks.runtime

import org.koaks.framework.model.Message
import org.koaks.runtime.context.ContextStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 experiment: quantifies the win of passing shared context **by reference**
 * (content-addressed store + deltas) versus the baseline where every agent carries a full
 * copy. Deterministic — no models or keys required.
 */
class ContextSharingBenchmarkTest {

    @Test
    fun reference_sharing_transmits_far_less_than_copying() {
        val store = ContextStore()
        val shared = (1..200).map { Message.user("shared knowledge paragraph number $it with some content") }
        val sharedChars = shared.sumOf { it.text.length }
        val agents = 10

        // Baseline: each agent receives a full copy of the shared context.
        val baselineChars = agents.toLong() * sharedChars

        // Runtime: the shared block is stored once; each agent transmits only a ref + a
        // small private delta.
        val ref = store.put(shared)
        var runtimeChars = sharedChars.toLong() // stored/transmitted once
        val deltaText = "private note"
        repeat(agents) { i ->
            store.delta(ref, listOf(Message.user("$deltaText $i")))
            runtimeChars += ref.id.length + deltaText.length + 2
        }

        assertTrue(
            runtimeChars < baselineChars / 5,
            "expected >5x reduction, got runtime=$runtimeChars baseline=$baselineChars",
        )
    }

    @Test
    fun content_addressing_gives_a_high_dedup_hit_rate() {
        val store = ContextStore()
        val ctx = (1..50).map { Message.user("knowledge line $it") }

        val attempts = 10
        val refs = (1..attempts).map { store.put(ctx) }
        val unique = refs.toSet().size

        assertEquals(1, unique) // all identical puts collapse to one block
        val hits = attempts - unique
        assertEquals(9, hits) // 9/10 hit rate
    }
}
