package org.koaks.runtime.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.AgentId
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Usage
import org.koaks.runtime.run.RunId
import org.koaks.runtime.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunRecordTest {
    @Test
    fun lifecycle_distinguishes_conversation_queue_admission_wait_and_pause() {
        val run = record()
        run.markRunning() // A run must first pass admission.
        assertEquals(RunState.CREATED, run.snapshot.state)
        run.markThreadQueued()
        assertEquals(RunState.THREAD_QUEUED, run.snapshot.state)
        run.markReady()
        run.markRunning()
        run.markWaiting()
        assertEquals(RunState.WAITING, run.snapshot.state)
        run.markReady() // Re-admission does not turn an existing wait into a new run.
        assertEquals(RunState.WAITING, run.snapshot.state)
        run.markRunning()
        run.markPaused()
        assertEquals(RunState.SUSPENDED, run.snapshot.state)
        run.markRunning()
        assertTrue(run.beginCommitting())
        run.markFinished(Usage.ZERO)
        assertEquals(RunState.FINISHED, run.snapshot.state)
    }

    @Test
    fun cancellation_and_commit_have_deterministic_winners() {
        val cancelled = running()
        cancelled.markCancelled()
        assertFalse(cancelled.beginCommitting())
        cancelled.markFinished(Usage.ZERO)
        assertEquals(RunState.CANCELLED, cancelled.snapshot.state)

        val committing = running()
        assertTrue(committing.beginCommitting())
        committing.markCancelled()
        assertEquals(RunState.COMMITTING, committing.snapshot.state)
        committing.markFinished(Usage.ZERO)
        assertEquals(RunState.FINISHED, committing.snapshot.state)
    }

    @Test
    fun terminal_states_reject_every_later_lifecycle_operation() {
        listOf(RunState.FINISHED, RunState.FAILED, RunState.CANCELLED).forEach { terminal ->
            val run = running()
            when (terminal) {
                RunState.FINISHED -> { run.beginCommitting(); run.markFinished(Usage.ZERO) }
                RunState.FAILED -> run.markFailed(error(), Usage.ZERO)
                else -> run.markCancelled()
            }
            run.markThreadQueued(); run.markReady(); run.markRunning(); run.markWaiting(); run.markPaused()
            assertFalse(run.beginCommitting())
            run.markCancelled(); run.markFailed(error(), Usage.ZERO); run.markFinished(Usage.ZERO)
            assertEquals(terminal, run.snapshot.state)
        }
    }

    @Test
    fun initialization_failure_can_finish_a_run_before_admission() {
        val run = record()
        run.markThreadQueued()
        run.markFailed(error(), Usage.ZERO)
        assertEquals(RunState.FAILED, run.snapshot.state)
        assertEquals("memory failed", run.snapshot.error?.message)
    }

    @Test
    fun a_timeout_can_fail_an_explicitly_paused_run() {
        val run = running()
        run.markPaused()
        run.markFailed(AgentError.Timeout("agent wall-clock", 10), Usage.ZERO)
        assertEquals(RunState.FAILED, run.snapshot.state)
    }

    @Test
    fun cancellation_racing_commit_never_overwrites_the_winner() = runTest {
        repeat(100) {
            val run = running()
            val start = CompletableDeferred<Unit>()
            val commit = launch(Dispatchers.Default) {
                start.await()
                if (run.beginCommitting()) run.markFinished(Usage.ZERO)
            }
            val cancel = launch(Dispatchers.Default) { start.await(); run.markCancelled() }
            start.complete(Unit)
            joinAll(commit, cancel)
            assertTrue(run.snapshot.state == RunState.FINISHED || run.snapshot.state == RunState.CANCELLED)
        }
    }

    private fun record() = RunRecord(RunId(1), AgentId("test"), "test", null, 0, null, null)
    private fun running() = record().also { it.markReady(); it.markRunning() }
    private fun error() = AgentError.ModelError("memory failed", retriable = false)
}
