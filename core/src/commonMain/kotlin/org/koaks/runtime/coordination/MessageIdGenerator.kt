package org.koaks.runtime.coordination

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Runtime-local message id source. */
internal class MessageIdGenerator {
    private val sequence = MutableStateFlow(0L)
    fun next(): Long = sequence.getAndUpdate { it + 1 }
}
