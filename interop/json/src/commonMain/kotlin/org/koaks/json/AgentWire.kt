package org.koaks.json

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.AgentState
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.tool.ToolOutputStream
import org.koaks.framework.tool.ToolProgress

internal fun TerminationReason.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is TerminationReason.MaxSteps -> {
            put("type", JsonPrimitive("max_steps"))
            put("max_steps", JsonPrimitive(maxSteps))
        }
        is TerminationReason.MaxTokens -> {
            put("type", JsonPrimitive("max_tokens"))
            put("max_tokens", JsonPrimitive(maxTokens))
        }
        is TerminationReason.RunBudgetSteps -> {
            put("type", JsonPrimitive("run_budget_steps"))
            put("max_total_steps", JsonPrimitive(maxTotalSteps))
        }
        is TerminationReason.RunBudgetTokens -> {
            put("type", JsonPrimitive("run_budget_tokens"))
            put("max_total_tokens", JsonPrimitive(maxTotalTokens))
        }
        is TerminationReason.Custom -> {
            put("type", JsonPrimitive("custom"))
            put("message", JsonPrimitive(message))
        }
    }
}

internal fun AgentResult.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is AgentResult.Completed -> put("status", JsonPrimitive("completed"))
        is AgentResult.Incomplete -> {
            put("status", JsonPrimitive("incomplete"))
            put("reason", reason.toWireJson())
        }
        is AgentResult.Terminated -> {
            put("status", JsonPrimitive("terminated"))
            put("reason", reason.toWireJson())
        }
        is AgentResult.Failed -> {
            put("status", JsonPrimitive("failed"))
            put("error", error.toWireJson())
        }
    }
    put("text", JsonPrimitive(text))
    put("message", message.toWireJson())
    put("usage", usage.toWireJson())
}

internal fun AgentEvent.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is AgentEvent.TextDelta -> {
            put("type", JsonPrimitive("text_delta"))
            put("text", JsonPrimitive(text))
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
        }
        is AgentEvent.ReasoningDelta -> {
            put("type", JsonPrimitive("reasoning_delta"))
            put("text", JsonPrimitive(text))
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
            put("kind", JsonPrimitive(kind.name.lowercase()))
        }
        is AgentEvent.Model -> {
            put("type", JsonPrimitive("model"))
            put("event", event.toWireJson())
            put("step", JsonPrimitive(step))
            put(
                "phase",
                JsonPrimitive(if (phase == ModelCallPhase.Normal) "normal" else "structured_finalization"),
            )
        }
        is AgentEvent.ToolCallRequested -> {
            put("type", JsonPrimitive("tool_call_requested"))
            put("call", call.toWireJson())
        }
        is AgentEvent.ToolResult -> {
            put("type", JsonPrimitive("tool_result"))
            put("call_id", JsonPrimitive(callId))
            put("output", JsonPrimitive(output))
            put("is_error", JsonPrimitive(isError))
        }
        is AgentEvent.ToolProgress -> {
            put("type", JsonPrimitive("tool_progress"))
            put("call_id", JsonPrimitive(callId))
            put("progress", progress.toWireJson())
        }
        is AgentEvent.StepCompleted -> {
            put("type", JsonPrimitive("step_completed"))
            put("step", JsonPrimitive(step))
        }
        is AgentEvent.Completed -> {
            put("type", JsonPrimitive("completed"))
            put("message", message.toWireJson())
            put("usage", usage.toWireJson())
        }
        is AgentEvent.Incomplete -> {
            put("type", JsonPrimitive("incomplete"))
            put("message", message.toWireJson())
            put("usage", usage.toWireJson())
            put("reason", reason.toWireJson())
        }
        is AgentEvent.Terminated -> {
            put("type", JsonPrimitive("terminated"))
            put("message", message.toWireJson())
            put("usage", usage.toWireJson())
            put("reason", reason.toWireJson())
        }
        is AgentEvent.Failed -> {
            put("type", JsonPrimitive("failed"))
            put("error", error.toWireJson())
            put("usage", usage.toWireJson())
        }
    }
}

internal fun ToolProgress.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is ToolProgress.Output -> {
            put("type", JsonPrimitive("output"))
            put("text", JsonPrimitive(text))
            put("stream", JsonPrimitive(if (stream == ToolOutputStream.Stdout) "stdout" else "stderr"))
        }
        is ToolProgress.Status -> {
            put("type", JsonPrimitive("status"))
            put("message", JsonPrimitive(message))
        }
        is ToolProgress.Custom -> {
            put("type", JsonPrimitive("custom"))
            put("kind", JsonPrimitive(kind))
            put("payload", payload)
        }
    }
}

internal fun AgentState.toWireJson(): JsonObject = buildJsonObject {
    put("items", buildJsonArray { items.forEach { add(it.toWireJson()) } })
    instructions?.let { put("instructions", JsonPrimitive(it)) }
    checkpoint?.let { put("checkpoint", it.toWireJson()) }
    put("global_step", JsonPrimitive(globalStep))
    put("local_step", JsonPrimitive(localStep))
    put("usage", usage.toWireJson())
    put("active_agent_name", JsonPrimitive(activeAgentName))
}
