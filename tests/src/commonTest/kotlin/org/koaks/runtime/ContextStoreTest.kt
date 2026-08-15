package org.koaks.runtime

import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.displayText
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
        val a = store.put(listOf(ModelItem.user("hello"), ModelItem.assistant("hi")))
        val b = store.put(listOf(ModelItem.user("hello"), ModelItem.assistant("hi")))
        assertEquals(a, b)

        val c = store.put(listOf(ModelItem.user("different")))
        assertNotEquals(a, c)
    }

    @Test
    fun delta_shares_parent_and_only_stores_the_delta() {
        val store = ContextStore()
        val base = store.put(listOf(ModelItem.user("shared-1"), ModelItem.user("shared-2")))
        val derived = store.delta(base, listOf(ModelItem.user("added")))

        // The delta block stores ONLY the added message (copy-on-write, no duplication).
        assertEquals(listOf("added"), store.get(derived)!!.messages.map { it.displayText() })

        // Resolving the delta yields parent + delta, in order.
        assertEquals(
            listOf("shared-1", "shared-2", "added"),
            store.resolve(derived, anyone).map { it.displayText() },
        )
        // The parent is untouched.
        assertEquals(listOf("shared-1", "shared-2"), store.resolve(base, anyone).map { it.displayText() })
    }

    @Test
    fun private_blocks_are_readable_only_by_owner() {
        val store = ContextStore()
        val owner = RunId(1)
        val other = RunId(2)
        val secret = store.put(listOf(ModelItem.user("classified")), scope = ContextScope.PRIVATE, owner = owner)

        assertEquals(listOf("classified"), store.resolve(secret, owner).map { it.displayText() })
        assertFailsWith<ContextAccessException> { store.resolve(secret, other) }
    }

    @Test
    fun global_and_task_blocks_are_readable_by_all() {
        val store = ContextStore()
        val global = store.put(listOf(ModelItem.user("g")), scope = ContextScope.GLOBAL)
        val task = store.put(listOf(ModelItem.user("t")), scope = ContextScope.TASK)
        assertEquals(listOf("g"), store.resolve(global, anyone).map { it.displayText() })
        assertEquals(listOf("t"), store.resolve(task, null).map { it.displayText() })
    }
}
