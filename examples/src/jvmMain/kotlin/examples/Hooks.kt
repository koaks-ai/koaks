package examples

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.tool.ToolOutcome
import org.koaks.provider.openai.openai
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Showcases all four typed hook points wired through one agent:
 *
 *  - onModelCall.before — inject RAG context into the request (skipped on the
 *    structured-finalization phase so it never pollutes the final JSON step).
 *  - onModelCall.after  — lazily wrap the model stream to collect metrics. The
 *    transformer NEVER collects; it only chains a lazy `onEach`, so the runner
 *    stays the single subscriber.
 *  - onToolCall.before  — human-in-the-loop approval for a side-effecting tool,
 *    suspending in-process on a Deferred until another coroutine approves.
 *  - onToolCall.after   — truncate noisy tool output before it returns to the model.
 *
 * See also the built-in hooks `Guardrail` and `HumanApproval` (install(...)) for
 * the common deny/approve cases without writing the DSL by hand.
 */
fun main() = runBlocking {
    // Stands in for an async approval source (a UI prompt, an approval queue, ...).
    val approval = CompletableDeferred<Boolean>()
    // Cross-call metric — use a thread-safe counter, not a Hook field: the same agent
    // can be run concurrently and tool calls execute in parallel.
    val streamedChars = AtomicInteger(0)

    val agent = agent {
        id = "hooked-assistant"
        name = "hook-demo-agent"
        model {
            openai(
                baseUrl = EnvTools.loadValue("BASE_URL"),
                apiKey = EnvTools.loadValue("API_KEY"),
                modelName = "qwen3.7-plus",
            ) {
                reasoningEffort = "medium"
            }
        }
        tools {
            tool<NoInput>(
                name = "read_profile",
                description = "读取用户资料摘要",
            ) {
                "用户偏好: 简洁回答; 常驻城市: 西安; 输出不要超过三句话。"
            }
            tool<NoInput>(
                name = "send_notification",
                description = "发送一条外部通知",
                hasSideEffects = true,
            ) {
                "通知已发送给用户。附加审计日志: ${"x".repeat(80)}"
            }
        }
        hook {
            onModelCall {
                // before: prepend retrieved context — but only on normal loop steps.
                before { ctx ->
                    if (ctx.phase == ModelCallPhase.StructuredFinalization) {
                        ctx.request
                    } else {
                        ctx.request.copy(
                            messages = listOf(
                                Message.system("RAG: 用户要求回答尽量短，优先使用本地上下文。"),
                            ) + ctx.request.messages,
                        )
                    }
                }
                // after: observe the stream lazily; never collect it here.
                after { _, stream ->
                    stream.onEach { event ->
                        if (event is ModelEvent.TextDelta) streamedChars.addAndGet(event.text.length)
                    }
                }
            }
            onToolCall {
                // before: gate the side-effecting tool on human approval (suspends).
                before { ctx ->
                    when {
                        ctx.call.name != "send_notification" -> Proceed
                        approval.await() -> Proceed
                        else -> Deny("notification was not approved")
                    }
                }
                // after: keep noisy tool output from flooding the next model step.
                after { _, outcome ->
                    if (outcome is ToolOutcome.Success && outcome.output.length > 48) {
                        outcome.copy(output = outcome.output.take(48) + "…")
                    } else {
                        outcome
                    }
                }
            }
        }
    }

    // A reviewer approves the notification a moment later — the before hook resumes.
    launch {
        delay(15.seconds)
        println("[reviewer] approving send_notification")
        approval.complete(true)
    }

    agent.use {
        it.stream("读取资料并给我一个简短总结。并且，发送通知。").collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> print(event.text)
                is AgentEvent.ToolCallRequested -> println("\n[tool call] ${event.call.name}")
                is AgentEvent.ToolResult ->
                    println("[tool result${if (event.isError) " error" else ""}] ${event.output}")

                is AgentEvent.Completed -> println("\n[done]")
                is AgentEvent.Failed -> println("\n[error] ${event.error.message}")
                is AgentEvent.Terminated -> println("\n[terminated] ${event.reason}")
                else -> Unit
            }
        }
    }

    println("[metrics] streamed text chars = ${streamedChars.get()}")
}
