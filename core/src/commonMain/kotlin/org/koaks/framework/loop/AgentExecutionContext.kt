package org.koaks.framework.loop

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A neutral hook, carried in the coroutine context, that lets whoever runs an agent
 * observe when an execution branch blocks and resumes. The agent loop itself has one
 * root branch, and it forks a branch per parallel tool call ([AgentRunner]'s tool step);
 * a runtime can use this to admit/park a concurrency slot so that "waiting" means "off
 * the CPU" without core ever depending on the runtime.
 *
 * The concrete implementation (e.g. a per-instance activity gate) is supplied by the
 * runtime. All methods must be safe to call from multiple branches concurrently.
 */
abstract class AgentExecutionContext protected constructor() :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentExecutionContext>

    /** Marks the current branch as blocked. Calls may be nested but must be balanced. */
    abstract suspend fun enterWaiting()

    /** Balances [enterWaiting], restoring the branch after its outermost wait. */
    abstract suspend fun leaveWaiting()

    /**
     * Runs [block] with the current branch marked waiting for its duration, restoring it
     * to runnable afterwards (or completing it if the branch was cancelled). This is the
     * single entry point every runtime-aware blocking wait should use.
     */
    open suspend fun <T> waiting(block: suspend () -> T): T {
        enterWaiting()
        try {
            return block()
        } finally {
            leaveWaiting()
        }
    }

    /**
     * Reserves a new runnable branch and returns a handle for running work under it.
     * Every independently executing child coroutine that can call [waiting] must use its
     * own branch; coroutine-context elements are inherited, so launching unregistered
     * parallel children would otherwise make them share one branch identity.
     *
     * Callers fork every branch on the parent coroutine *before* the parent starts
     * awaiting them (a sequential fork loop that precedes `awaitAll`), so the instance
     * never momentarily looks fully-waiting between "root started awaiting" and "children
     * registered" — which would thrash a concurrency slot.
     */
    abstract suspend fun forkBranch(): ExecutionBranch

    /** Records that this run actually entered a tool execution declaring external side effects. */
    abstract fun markSideEffect()
}

/** A reserved execution branch. [run] executes [block] under this branch, completing it on exit. */
interface ExecutionBranch {
    suspend fun <T> run(block: suspend () -> T): T
}
