package org.koaks.runtime.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.koaks.runtime.run.RunHandle
import org.koaks.runtime.run.RunId

/** Runtime-owned records retained until explicit pruning or shutdown. */
internal class RunRegistry {
    private val sequence = MutableStateFlow(0L)
    val records = MutableStateFlow<Map<RunId, RunRecord>>(emptyMap())
    val handles = MutableStateFlow<Map<RunId, RunHandle>>(emptyMap())
    val journals = MutableStateFlow<Map<RunId, RunJournal>>(emptyMap())
    fun nextId(): RunId = RunId(sequence.getAndUpdate { it + 1 })
}
