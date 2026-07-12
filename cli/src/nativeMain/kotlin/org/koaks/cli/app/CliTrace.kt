@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.app

import kotlinx.cinterop.ExperimentalForeignApi
import org.koaks.cli.config.Environment
import org.koaks.cli.config.value
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentState
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.model.ModelEvent
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs
import kotlin.time.TimeSource

/**
 * Opt-in, low-volume lifecycle trace for diagnosing pauses in the interactive CLI.
 *
 * The trace deliberately records event kinds, sizes, and timings instead of prompts,
 * model text, tool arguments, or tool output. This keeps a diagnostic log useful
 * without copying conversation content or credentials into it.
 */
internal class CliTrace(
    private val writer: TraceWriter?,
    private val elapsedMillis: () -> Long = monotonicElapsedMillis(),
) : AgentListener, AutoCloseable {
    private var sequence = 0L
    private var lastLogMillis = 0L
    private var currentStep = 0
    private var modelEventsInStep = 0
    private var modelRequestStartedMillis = 0L
    private var turn = 0
    private var agentEventsInTurn = 0
    private var currentAgentEventIndex = 0
    private var agentEventReceivedMillis = 0L
    private var textRunActive = false
    private var reasoningRunActive = false
    private val toolStartedMillis = mutableMapOf<String, Long>()

    val enabled: Boolean get() = writer != null

    fun turnStarted(inputLength: Int) {
        turn++
        textRunActive = false
        reasoningRunActive = false
        agentEventsInTurn = 0
        currentAgentEventIndex = 0
        toolStartedMillis.clear()
        log("turn.start", "turn=$turn input_chars=$inputLength")
    }

    fun collectorStarted() = log("collector.start", "turn=$turn")

    fun eventReceived(event: AgentEvent) {
        agentEventsInTurn++
        currentAgentEventIndex = agentEventsInTurn
        agentEventReceivedMillis = elapsedMillis()
        log(
            "agent.event.received",
            "turn=$turn index=$currentAgentEventIndex type=${event.kind()}${event.sizeFields()}",
        )
        when (event) {
            is AgentEvent.TextDelta -> {
                if (!textRunActive) log("agent.text.first", "turn=$turn step=$currentStep chars=${event.text.length}")
                textRunActive = true
                reasoningRunActive = false
            }

            is AgentEvent.ReasoningDelta -> {
                if (!reasoningRunActive) {
                    log("agent.reasoning.first", "turn=$turn step=$currentStep chars=${event.text.length}")
                }
                reasoningRunActive = true
                textRunActive = false
            }

            is AgentEvent.ToolCallRequested -> {
                toolStartedMillis[event.call.id] = elapsedMillis()
                textRunActive = false
                reasoningRunActive = false
                log("tool.call", "turn=$turn step=$currentStep id=${event.call.id.safe()} name=${event.call.name.safe()}")
            }

            is AgentEvent.ToolResult -> {
                val duration = toolStartedMillis.remove(event.callId)?.let { elapsedMillis() - it }
                log(
                    "tool.result",
                    "turn=$turn step=$currentStep id=${event.callId.safe()} error=${event.isError}" +
                        " output_chars=${event.output.length}" +
                        (duration?.let { " tool_ms=$it" } ?: ""),
                )
            }

            is AgentEvent.StepCompleted -> log("step.completed", "turn=$turn step=${event.step}")
            is AgentEvent.Completed -> log("turn.completed", "turn=$turn step=$currentStep")
            is AgentEvent.Terminated -> log("turn.terminated", "turn=$turn step=$currentStep reason=${event.reason}")
            is AgentEvent.Failed -> log(
                "turn.failed",
                "turn=$turn step=$currentStep error=${event.error::class.simpleName} message_chars=${event.error.message.length}",
            )
        }
    }

    /** Called after EventPrinter has written the event and performed any required stdout flush. */
    fun eventRendered(event: AgentEvent) {
        log(
            "agent.event.rendered",
            "turn=$turn index=$currentAgentEventIndex type=${event.kind()}" +
                " render_ms=${elapsedMillis() - agentEventReceivedMillis}",
        )
        if (event is AgentEvent.ToolResult) {
            log("tool.result.rendered", "turn=$turn step=$currentStep id=${event.callId.safe()}")
        }
    }

    fun renderStage(event: AgentEvent, stage: String, renderedChars: Int? = null) {
        log(
            "agent.render.stage",
            "turn=$turn index=$currentAgentEventIndex type=${event.kind()} stage=$stage" +
                (renderedChars?.let { " rendered_chars=$it" } ?: ""),
        )
    }

    fun markdownFallback(reason: String, state: String, pendingChars: Int, errorType: String?) {
        log(
            "markdown.fallback",
            "turn=$turn index=$currentAgentEventIndex reason=${reason.safe()} state=${state.safe()}" +
                " pending_chars=$pendingChars" +
                (errorType?.let { " error=${it.safe()}" } ?: ""),
        )
    }

    fun collectorCompleted() = log("collector.completed", "turn=$turn")

    fun collectorFailed(error: Throwable) = log(
        "collector.failed",
        "turn=$turn error=${error::class.simpleName} message_chars=${error.message?.length ?: 0}",
    )

    override fun onStep(state: AgentState) {
        currentStep = state.step + 1
        modelEventsInStep = 0
        modelRequestStartedMillis = elapsedMillis()
        textRunActive = false
        reasoningRunActive = false
        log(
            "model.request.start",
            "turn=$turn step=$currentStep messages=${state.messages.size} global_step=${state.globalStep}",
        )
    }

    override fun onModelEvent(event: ModelEvent) {
        modelEventsInStep++
        if (modelEventsInStep == 1) {
            log(
                "model.response.first_event",
                "turn=$turn step=$currentStep wait_ms=${elapsedMillis() - modelRequestStartedMillis} type=${event.kind()}",
            )
        }
        log(
            "model.event",
            "turn=$turn step=$currentStep index=$modelEventsInStep type=${event.kind()}${event.sizeFields()}",
        )
        when (event) {
            is ModelEvent.Completed -> log("model.response.completed", "turn=$turn step=$currentStep events=$modelEventsInStep")
            is ModelEvent.Failed -> log("model.response.failed", "turn=$turn step=$currentStep events=$modelEventsInStep")
            else -> Unit
        }
    }

    private fun log(name: String, fields: String) {
        val sink = writer ?: return
        val now = elapsedMillis()
        sequence++
        val delta = now - lastLogMillis
        lastLogMillis = now
        sink.write("seq=$sequence elapsed_ms=$now delta_ms=$delta event=$name $fields\n")
    }

    override fun close() {
        log("trace.closed", "turns=$turn")
        writer?.close()
    }

    internal companion object {
        const val TRACE_FILE_ENV = "KOAKS_TRACE_FILE"

        fun open(environment: Environment): CliTrace {
            val path = environment.value(TRACE_FILE_ENV) ?: return CliTrace(null)
            return CliTrace(FileTraceWriter.open(path))
        }

        private fun monotonicElapsedMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}

internal interface TraceWriter : AutoCloseable {
    fun write(line: String)
}

private class FileTraceWriter(private val file: kotlinx.cinterop.CPointer<FILE>) : TraceWriter {
    override fun write(line: String) {
        fputs(line, file)
        fflush(file)
    }

    override fun close() {
        fclose(file)
    }

    companion object {
        fun open(path: String): TraceWriter? = fopen(path, "ab")?.let(::FileTraceWriter)
    }
}

private fun ModelEvent.kind(): String = when (this) {
    is ModelEvent.TextDelta -> "text"
    is ModelEvent.ReasoningDelta -> "reasoning"
    is ModelEvent.ToolCallDelta -> "tool_call_delta"
    is ModelEvent.ToolCallCompleted -> "tool_call_completed"
    is ModelEvent.Completed -> "completed"
    is ModelEvent.Failed -> "failed"
}

private fun ModelEvent.sizeFields(): String = when (this) {
    is ModelEvent.TextDelta -> " chars=${text.length}"
    is ModelEvent.ReasoningDelta -> " chars=${text.length}"
    is ModelEvent.ToolCallDelta ->
        " name_chars=${nameDelta?.length ?: 0} arguments_chars=${argumentsDelta?.length ?: 0}"
    is ModelEvent.ToolCallCompleted ->
        " name_chars=${call.name.length} arguments_chars=${call.arguments.length}"
    is ModelEvent.Completed -> ""
    is ModelEvent.Failed -> " message_chars=${error.message.length}"
}

private fun AgentEvent.kind(): String = when (this) {
    is AgentEvent.TextDelta -> "text"
    is AgentEvent.ReasoningDelta -> "reasoning"
    is AgentEvent.ToolCallRequested -> "tool_call"
    is AgentEvent.ToolResult -> "tool_result"
    is AgentEvent.StepCompleted -> "step_completed"
    is AgentEvent.Completed -> "completed"
    is AgentEvent.Terminated -> "terminated"
    is AgentEvent.Failed -> "failed"
}

private fun AgentEvent.sizeFields(): String = when (this) {
    is AgentEvent.TextDelta -> " chars=${text.length}"
    is AgentEvent.ReasoningDelta -> " chars=${text.length}"
    is AgentEvent.ToolCallRequested ->
        " name_chars=${call.name.length} arguments_chars=${call.arguments.length}"
    is AgentEvent.ToolResult -> " output_chars=${output.length}"
    is AgentEvent.StepCompleted -> " step=$step"
    is AgentEvent.Completed -> " message_chars=${message.text.length}"
    is AgentEvent.Terminated -> " message_chars=${message.text.length}"
    is AgentEvent.Failed -> " message_chars=${error.message.length}"
}

private fun String.safe(): String = replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(120)
