package org.koaks.runtime.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Cooperative pause/resume gate for one run instance. The instance checks it at safe
 * points (between outward events); execution is cooperative and never preempted.
 */
internal class RunControl {
    private val paused = MutableStateFlow(false)

    val isPaused: Boolean get() = paused.value

    fun pause() {
        paused.value = true
    }

    fun resume() {
        paused.value = false
    }

    /** Suspends while paused; returns immediately when running. */
    suspend fun awaitResumed() {
        paused.first { !it }
    }
}
