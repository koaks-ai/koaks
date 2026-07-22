package org.koaks.runtime.resource

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.ExecutionBranch
import org.koaks.runtime.acb.Acb
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.sched.SlotLease
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-instance activity gate: the bridge between an instance's parallel execution
 * branches (root loop + one per concurrent tool call) and its single scheduler
 * [SlotLease].
 *
 * The invariant it maintains: **while at least one branch is runnable, the instance
 * holds its slot; when every branch is waiting or completed, the slot is parked.** This
 * is why `maxConcurrency` counts instances that are actually running rather than merely
 * admitted, and why a parent that fans out N tool calls — each awaiting a child — does
 * not deadlock at `maxConcurrency = 1`: once all branches block, the slot frees and the
 * children are admitted.
 *
 * A single [gate] mutex linearizes every count/state transition, so concurrent branches
 * are safe. The gate only ever calls into the lease (never the reverse), so there is no
 * lock cycle. Holding [gate] across the suspending [SlotLease.unpark] is safe: unpark is
 * only reached at `runnable == 0`, and the permit that unblocks it is freed by a
 * *different* instance, not by any branch of this one.
 *
 * The concrete branch identity is tracked so nested waits on the same branch are balanced
 * (only the outermost wait changes instance activity) and so a branch that is cancelled
 * mid-wait is retired rather than counted as runnable again. Parallel child coroutines
 * must be registered as separate branches through [forkBranch]; sharing one branch tag
 * concurrently would otherwise collapse distinct runnable continuations into one identity.
 */
internal class InstanceActivityGate(
    private val runId: RunId,
    private val acb: Acb,
    private val emit: (RuntimeEvent) -> Unit,
    private val onSideEffect: () -> Unit = {},
) : AgentExecutionContext() {

    private enum class BranchState { RUNNABLE, WAITING }

    private data class BranchRecord(
        var state: BranchState,
        var waitDepth: Int = 0,
    )

    private val gate = Mutex()
    private val branches = HashMap<Long, BranchRecord>()
    private var branchSeq = 0L
    private var runnable = 0

    /**
     * The scheduler slot, bound once the instance is admitted ([attachLease]). Until then
     * a no-op lease, so a park/unpark that somehow races admission is harmless. Only read
     * and written under [gate], so no separate synchronization is needed.
     */
    private var lease: SlotLease = NoopLease

    init {
        // The root loop is branch 0, runnable from the start.
        branches[branchSeq++] = BranchRecord(BranchState.RUNNABLE)
        runnable = 1
    }

    /** Binds the scheduler slot once the instance is admitted. Runtime-internal. */
    internal suspend fun attachLease(l: SlotLease) {
        gate.withLock { lease = l }
    }

    /** The branch id for the current coroutine, or the root branch (0) when none is installed. */
    private suspend fun currentBranchId(): Long =
        currentCoroutineContext()[BranchTag]?.id ?: 0L

    override suspend fun enterWaiting() {
        val id = currentBranchId()
        gate.withLock {
            val branch = branches[id] ?: return
            branch.waitDepth++
            if (branch.waitDepth > 1) return
            check(branch.state == BranchState.RUNNABLE) { "branch $id is not runnable" }
            branch.state = BranchState.WAITING
            if (--runnable == 0) parkAndMarkWaiting()
        }
    }

    override suspend fun leaveWaiting() {
        val id = currentBranchId()
        gate.withLock {
            val branch = branches[id] ?: return
            if (branch.waitDepth == 0) return
            branch.waitDepth--
            if (branch.waitDepth > 0) return
            check(branch.state == BranchState.WAITING) { "branch $id is not waiting" }
            branch.state = BranchState.RUNNABLE
            if (runnable++ == 0) unparkAndMarkRunning()
        }
    }

    override suspend fun <T> waiting(block: suspend () -> T): T {
        val id = currentBranchId()
        enterWaiting()
        try {
            return block()
        } finally {
            // A branch cancelled mid-wait must retire, not resurrect itself as runnable
            // (which would falsely hold the slot). Capture liveness before entering
            // NonCancellable: that context is always active and cannot answer whether the
            // original branch was cancelled. A live branch re-acquires admission
            // cancellably; only terminal retirement is cancellation-immune.
            if (currentCoroutineContext().isActive) {
                leaveWaiting()
            } else {
                withContext(NonCancellable) { complete(id) }
            }
        }
    }

    override suspend fun forkBranch(): ExecutionBranch {
        val id = gate.withLock {
            val newId = branchSeq++
            branches[newId] = BranchRecord(BranchState.RUNNABLE)
            // Registered on the root coroutine before it awaits, so runnable only rises;
            // the slot is already held, no unpark needed.
            runnable++
            newId
        }
        return Branch(id)
    }

    override fun markSideEffect() = onSideEffect()

    private inner class Branch(private val id: Long) : ExecutionBranch {
        override suspend fun <T> run(block: suspend () -> T): T =
            withContext(BranchTag(id)) {
                try {
                    block()
                } finally {
                    withContext(NonCancellable) { complete(id) }
                }
            }
    }

    /** Retires a branch. A runnable branch completing may free the slot. */
    private suspend fun complete(id: Long) {
        gate.withLock {
            when (branches.remove(id)?.state) {
                BranchState.RUNNABLE -> if (--runnable == 0) parkAndMarkWaiting()
                BranchState.WAITING, null -> {
                    // WAITING already decremented runnable; null means already completed.
                }
            }
        }
    }

    /** Must hold [gate]. All branches idle → mark WAITING and release the slot. */
    private suspend fun parkAndMarkWaiting() {
        if (acb.snapshot.state == LifecycleState.RUNNING) {
            acb.setState(LifecycleState.WAITING)
            val snapshot = acb.snapshot
            emit(RuntimeEvent.Waiting(runId, snapshot.agentId, snapshot.threadId, snapshot.turnId))
        }
        lease.park()
    }

    /** Must hold [gate]. A branch became runnable → re-acquire the slot and mark RUNNING. */
    private suspend fun unparkAndMarkRunning() {
        lease.unpark()
        if (acb.snapshot.state == LifecycleState.WAITING) {
            acb.markRunning()
            emit(
                RuntimeEvent.Running(
                    runId,
                    acb.snapshot.agentId,
                    acb.snapshot.threadId,
                    acb.snapshot.turnId,
                    acb.snapshot.agentName,
                ),
            )
        }
    }

    /** Coroutine-context tag marking which branch a coroutine belongs to. */
    private class BranchTag(val id: Long) : AbstractCoroutineContextElement(BranchTag) {
        companion object Key : CoroutineContext.Key<BranchTag>
    }

    private object NoopLease : SlotLease {
        override suspend fun park() {}
        override suspend fun unpark() {}
        override suspend fun close() {}
    }
}
