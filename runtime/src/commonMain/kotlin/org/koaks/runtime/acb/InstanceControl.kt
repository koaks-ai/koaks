package org.koaks.runtime.acb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Cooperative pause/resume gate for one run instance. The instance checks it at safe
 * points (between outward events); there is no preemption in a pure-Kotlin kernel.
 */
internal class InstanceControl {
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
