package org.koaks.runtime

import org.koaks.framework.model.Message
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.context.ContextAccessException
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.context.ContextStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ContextStoreTest {

    private val anyone = RunId(999)

    @Test
    fun identical_content_is_deduplicated() {
        val store = ContextStore()
        val a = store.put(listOf(Message.user("hello"), Message.assistant("hi")))
        val b = store.put(listOf(Message.user("hello"), Message.assistant("hi")))
        assertEquals(a, b)

        val c = store.put(listOf(Message.user("different")))
        assertNotEquals(a, c)
    }

    @Test
    fun delta_shares_parent_and_only_stores_the_delta() {
        val store = ContextStore()
        val base = store.put(listOf(Message.user("shared-1"), Message.user("shared-2")))
        val derived = store.delta(base, listOf(Message.user("added")))

        // The delta block stores ONLY the added message (copy-on-write, no duplication).
        assertEquals(listOf("added"), store.get(derived)!!.messages.map { it.text })

        // Resolving the delta yields parent + delta, in order.
        assertEquals(
            listOf("shared-1", "shared-2", "added"),
            store.resolve(derived, anyone).map { it.text },
        )
        // The parent is untouched.
        assertEquals(listOf("shared-1", "shared-2"), store.resolve(base, anyone).map { it.text })
    }

    @Test
    fun private_blocks_are_readable_only_by_owner() {
        val store = ContextStore()
        val owner = RunId(1)
        val other = RunId(2)
        val secret = store.put(listOf(Message.user("classified")), scope = ContextScope.PRIVATE, owner = owner)

        assertEquals(listOf("classified"), store.resolve(secret, owner).map { it.text })
        assertFailsWith<ContextAccessException> { store.resolve(secret, other) }
    }

    @Test
    fun global_and_task_blocks_are_readable_by_all() {
        val store = ContextStore()
        val global = store.put(listOf(Message.user("g")), scope = ContextScope.GLOBAL)
        val task = store.put(listOf(Message.user("t")), scope = ContextScope.TASK)
        assertEquals(listOf("g"), store.resolve(global, anyone).map { it.text })
        assertEquals(listOf("t"), store.resolve(task, null).map { it.text })
    }
}
