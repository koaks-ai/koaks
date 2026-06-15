package org.koaks.framework.memory

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.model.Message

private val logger = KotlinLogging.logger {}

/**
 * A multi-turn conversation bound to one [ThreadId]. Carries history so callers
 * never hand-pass a `memoryId`.
 *
 * The data flow:
 *  1. `history = memory.load(id)`  (already trimmed/summarized on the load side)
 *  2. `initial = system? + history + user`
 *  3. run the loop, buffering new messages locally (never touching memory mid-run)
 *  4. on [AgentEvent.Finished]: commit the whole turn atomically
 *     on failure/cancel: discard the buffer, leaving persistent history untouched
 *
 * The loop itself stays Memory-agnostic; All persistence lives here at the run
 * boundary.
 */
class AgentThread(private val agent: Agent, val id: ThreadId) {

    /** Streams events while transparently loading history and committing on success. */
    fun stream(input: String): Flow<AgentEvent> = flow {
        val history = agent.memoryStore.load(id)
        val userMessage = Message.user(input)
        val initial = agent.withInstructions(history + userMessage)
        val buffer = TurnCommitBuffer(userMessage)

        agent.streamMessages(initial).collect { event ->
            buffer.observe(event)
            emit(event)
        }

        if (buffer.shouldCommit()) {
            // Faithful append of this run's new messages only (history is already persisted).
            agent.memoryStore.commit(id, buffer.messagesInOrder())
        } else {
            warnOnDiscardedSideEffects(buffer)
        }
    }

    /** Runs to terminal state, committing the turn on success. */
    suspend fun run(input: String): AgentResult {
        val history = agent.memoryStore.load(id)
        val userMessage = Message.user(input)
        val initial = agent.withInstructions(history + userMessage)
        val buffer = TurnCommitBuffer(userMessage)

        val events = mutableListOf<AgentEvent>()
        agent.streamMessages(initial).collect { event ->
            buffer.observe(event)
            events += event
        }

        if (buffer.shouldCommit()) {
            agent.memoryStore.commit(id, buffer.messagesInOrder())
        } else {
            warnOnDiscardedSideEffects(buffer)
        }

        val finished = events.filterIsInstance<AgentEvent.Finished>().lastOrNull()
        if (finished != null) return AgentResult(finished.message, finished.usage)
        val failed = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        return AgentResult(Message.assistant(""), org.koaks.framework.model.Usage.ZERO, failed?.error)
    }

    /**
     * `side-effect warning`: when a turn is discarded on failure, any tool with
     * external side effects may already have run, yet leaves no trace in persistent
     * history — risking a duplicate on the next run. We cannot detect that the tool
     * actually ran here (the loop owns that), so we warn whenever the agent exposes
     * any side-effecting tool and a turn was rolled back. Mitigations: idempotency
     * keys, a side-effect ledger, or narrower turn boundaries.
     */
    private fun warnOnDiscardedSideEffects(buffer: TurnCommitBuffer) {
        if (agent.hasSideEffectingTools && buffer.producedToolResults()) {
            logger.warn {
                "thread ${id.value}: a turn with side-effecting tools was rolled back; " +
                    "already-performed side effects are NOT recorded in persistent history and " +
                    "may be repeated on the next run (use an idempotency key / side-effect ledger / " +
                    "narrower turn boundary)"
            }
        }
    }
}
