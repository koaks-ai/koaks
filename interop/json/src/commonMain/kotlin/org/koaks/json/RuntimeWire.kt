package org.koaks.json

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.RunEventEnvelope
import org.koaks.runtime.acb.RunEventPayload
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.observe.RuntimeMetrics
import org.koaks.runtime.thread.ThreadSnapshot

internal fun AcbSnapshot.toWireJson(): JsonObject = buildJsonObject {
    put("run_id", JsonPrimitive(runId.value.toString()))
    put("agent_id", JsonPrimitive(agentId.value))
    put("agent_name", JsonPrimitive(agentName))
    threadId?.let { put("thread_id", JsonPrimitive(it.value)) }
    turnId?.let { put("turn_id", JsonPrimitive(it.value.toString())) }
    put("state", JsonPrimitive(state.name.lowercase()))
    put("priority", JsonPrimitive(priority))
    parent?.let { put("parent", JsonPrimitive(it.value.toString())) }
    correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
    put("children", buildJsonArray { children.forEach { add(JsonPrimitive(it.value.toString())) } })
    put("accepting_children", JsonPrimitive(acceptingChildren))
    put("usage", usage.toWireJson())
    put("steps_completed", JsonPrimitive(stepsCompleted))
    put("tool_calls", JsonPrimitive(toolCalls))
    put("elapsed_ms", JsonPrimitive(elapsedMillis))
    error?.let { put("error", it.toWireJson()) }
}

internal fun RunEventEnvelope.toWireJson(): JsonObject = buildJsonObject {
    put("run_id", JsonPrimitive(runId.value.toString()))
    put("agent_id", JsonPrimitive(agentId.value))
    threadId?.let { put("thread_id", JsonPrimitive(it.value)) }
    turnId?.let { put("turn_id", JsonPrimitive(it.value.toString())) }
    correlationId?.let { put("correlation_id", JsonPrimitive(it)) }
    put("sequence", JsonPrimitive(sequence))
    put("timestamp_epoch_ms", JsonPrimitive(timestampEpochMillis))
    when (val value = payload) {
        is RunEventPayload.Agent -> {
            put("kind", JsonPrimitive("agent"))
            put("event", value.event.toWireJson())
        }
        is RunEventPayload.Lifecycle -> {
            put("kind", JsonPrimitive("lifecycle"))
            put("event", value.event.toWireJson())
        }
        is RunEventPayload.HistoryGap -> {
            put("kind", JsonPrimitive("history_gap"))
            put("requested_after", JsonPrimitive(value.requestedAfter))
            put("oldest_available", JsonPrimitive(value.oldestAvailable))
        }
    }
}

internal fun ThreadSnapshot.toWireJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id.value))
    put("memory_provider_id", JsonPrimitive(memoryProviderId.value))
    put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it.value)) } })
    activeTurn?.let { put("active_turn", JsonPrimitive(it.value.toString())) }
    put("queued_turns", buildJsonArray { queuedTurns.forEach { add(JsonPrimitive(it.value.toString())) } })
}

internal fun RuntimeMetrics.toWireJson(): JsonObject = buildJsonObject {
    put("total", JsonPrimitive(total))
    put("created", JsonPrimitive(created))
    put("thread_queued", JsonPrimitive(threadQueued))
    put("ready", JsonPrimitive(ready))
    put("running", JsonPrimitive(running))
    put("waiting", JsonPrimitive(waiting))
    put("suspended", JsonPrimitive(suspended))
    put("committing", JsonPrimitive(committing))
    put("finished", JsonPrimitive(finished))
    put("failed", JsonPrimitive(failed))
    put("cancelled", JsonPrimitive(cancelled))
    put("total_tokens", JsonPrimitive(totalTokens))
    put("total_steps", JsonPrimitive(totalSteps))
    put("total_tool_calls", JsonPrimitive(totalToolCalls))
}

internal fun RuntimeEvent.toWireJson(): JsonObject = buildJsonObject {
    fun base(type: String, run: String?, agent: String?, thread: String?, turn: String?) {
        put("type", JsonPrimitive(type))
        run?.let { put("run_id", JsonPrimitive(it)) }
        agent?.let { put("agent_id", JsonPrimitive(it)) }
        thread?.let { put("thread_id", JsonPrimitive(it)) }
        turn?.let { put("turn_id", JsonPrimitive(it)) }
    }
    when (this@toWireJson) {
        is RuntimeEvent.Spawned -> {
            base("spawned", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("agent_name", JsonPrimitive(agentName))
            put("priority", JsonPrimitive(priority))
            parent?.let { put("parent", JsonPrimitive(it.value.toString())) }
        }
        is RuntimeEvent.Running -> {
            base("running", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("agent_name", JsonPrimitive(agentName))
        }
        is RuntimeEvent.Waiting -> base("waiting", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.Suspended -> base("suspended", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.Resumed -> base("resumed", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.Finished -> {
            base("finished", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("usage", usage.toWireJson())
        }
        is RuntimeEvent.Incomplete -> {
            base("incomplete", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("reason", reason.toWireJson())
            put("usage", usage.toWireJson())
        }
        is RuntimeEvent.Terminated -> {
            base("terminated", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("reason", reason.toWireJson())
        }
        is RuntimeEvent.Failed -> {
            base("failed", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("error", error.toWireJson())
        }
        is RuntimeEvent.Cancelled -> base("cancelled", runId.value.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
        is RuntimeEvent.UnhandledChildFailure -> {
            base("unhandled_child_failure", childRunId.value.toString(), childAgentId.value, null, null)
            put("parent_run_id", JsonPrimitive(parentRunId.value.toString()))
            put("error", error.toWireJson())
        }
        is RuntimeEvent.SideEffectRollback -> base("side_effect_rollback", runId.value.toString(), agentId.value, threadId.value, turnId.value.toString())
        is RuntimeEvent.Retrying -> {
            base("retrying", runId?.value?.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("agent_name", JsonPrimitive(agentName))
            put("attempt", JsonPrimitive(attempt))
            put("delay_ms", JsonPrimitive(delayMillis))
        }
        is RuntimeEvent.CircuitOpen -> {
            base("circuit_open", runId?.value?.toString(), agentId.value, threadId?.value, turnId?.value?.toString())
            put("agent_name", JsonPrimitive(agentName))
        }
    }
}
