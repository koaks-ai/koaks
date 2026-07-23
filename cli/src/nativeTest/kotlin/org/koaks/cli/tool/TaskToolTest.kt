package org.koaks.cli.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.AgentRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskToolTest {
    @Serializable
    private data object NoArgs

    @Test
    fun failedChildUsesTheExplicitToolErrorChannel() = runBlocking {
        val child = childAgent(
            id = "koaks-cli-general",
            model = StaticModel(listOf(ModelEvent.Failed(AgentError.ModelError("child boom", false)))),
        )
        val parent = parentAgent(
            id = "task-failure-parent",
            task = TaskTool(SubagentType.GENERAL, child),
            calls = listOf(taskCall("task-1", "same description", "fail", "general")),
        )

        AgentRuntime().use { runtime ->
            val events = runtime.stream(parent, "go").toList()
            val result = events.filterIsInstance<AgentEvent.ToolResult>().single()

            assertTrue(result.isError)
            assertEquals("child boom", result.output)
            assertTrue(events.any { it is AgentEvent.Completed })
        }
    }

    @Test
    fun invalidTaskInputUsesTheExplicitToolErrorChannel() = runBlocking {
        val child = childAgent("koaks-cli-general", StaticModel(successEvents("unused")))
        val parent = parentAgent(
            id = "task-validation-parent",
            task = TaskTool(SubagentType.GENERAL, child),
            calls = listOf(taskCall("task-1", "", "prompt", "general")),
        )

        AgentRuntime().use { runtime ->
            val result = runtime.stream(parent, "go").toList()
                .filterIsInstance<AgentEvent.ToolResult>()
                .single()

            assertTrue(result.isError)
            assertContains(result.output, "description is required")
        }
    }

    @Test
    fun terminatedChildUsesTheExplicitToolErrorChannelWithPartialOutput() = runBlocking {
        val child = agent {
            id = "koaks-cli-general"
            model {
                custom(
                    StaticModel(
                        listOf(
                            ModelEvent.TextDelta("partial"),
                            ModelEvent.ToolCallCompleted(ToolCall("noop-1", "noop", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                    ),
                )
            }
            tools { tool<NoArgs>("noop", "no-op") { "ok" } }
            terminateAfter(maxSteps = 1)
        }
        val parent = parentAgent(
            id = "task-terminated-parent",
            task = TaskTool(SubagentType.GENERAL, child),
            calls = listOf(taskCall("task-1", "terminated task", "go", "general")),
        )

        AgentRuntime().use { runtime ->
            val result = runtime.stream(parent, "go").toList()
                .filterIsInstance<AgentEvent.ToolResult>()
                .single()

            assertTrue(result.isError)
            assertContains(result.output, "Task terminated")
            assertContains(result.output, "partial")
        }
    }

    @Test
    fun identicalDescriptionsReuseOneAgentDefinitionWithoutIdConflict() = runBlocking {
        val child = childAgent("koaks-cli-worker", StaticModel(successEvents("done")))
        val parent = parentAgent(
            id = "task-same-description-parent",
            task = TaskTool(SubagentType.WORKER, child),
            calls = listOf(
                taskCall("task-1", "same description", "slice one", "worker"),
                taskCall("task-2", "same description", "slice two", "worker"),
            ),
        )

        AgentRuntime { maxConcurrency = 3 }.use { runtime ->
            val results = runtime.stream(parent, "go").toList()
                .filterIsInstance<AgentEvent.ToolResult>()

            assertEquals(2, results.size)
            assertTrue(results.all { !it.isError })
        }
    }

    @Test
    fun fiveWorkersRunInParallelAndOneFailureDoesNotCancelTheOthers() = runBlocking {
        val model = ParallelWorkerModel(expectedWorkers = 5)
        val child = childAgent("koaks-cli-worker", model)
        val parent = parentAgent(
            id = "task-five-workers-parent",
            task = TaskTool(SubagentType.WORKER, child),
            calls = (0 until 5).map { index ->
                taskCall(
                    id = "task-$index",
                    description = "batch",
                    prompt = if (index == 2) "fail-$index" else "ok-$index",
                    type = "worker",
                )
            },
        )

        AgentRuntime { maxConcurrency = 8 }.use { runtime ->
            val events = runtime.stream(parent, "go").toList()
            val results = events.filterIsInstance<AgentEvent.ToolResult>()

            assertEquals(5, model.started)
            assertEquals(5, results.size)
            assertEquals(1, results.count { it.isError })
            assertEquals(4, results.count { !it.isError })
            assertTrue(events.any { it is AgentEvent.Completed })
        }
    }

    private fun parentAgent(id: String, task: TaskTool, calls: List<ToolCall>): Agent {
        val toolStep = buildList<ModelEvent> {
            calls.forEach { add(ModelEvent.ToolCallCompleted(it)) }
            add(ModelEvent.Completed(Usage.ZERO))
        }
        return agent {
            this.id = id
            model {
                custom(
                    ScriptedModel(
                        listOf(
                            toolStep,
                            listOf(ModelEvent.TextDelta("parent complete"), ModelEvent.Completed(Usage.ZERO)),
                        ),
                    ),
                )
            }
            tools { tool(task) }
            terminateAfter(maxSteps = 5)
        }
    }

    private fun childAgent(id: String, model: LanguageModel): Agent = agent {
        this.id = id
        model { custom(model) }
        terminateAfter(maxSteps = 5)
    }

    private fun taskCall(id: String, description: String, prompt: String, type: String): ToolCall =
        ToolCall(
            id = id,
            name = "Task",
            arguments =
                """{"description":"$description","prompt":"$prompt","subagent_type":"$type"}""",
        )

    private fun successEvents(text: String): List<ModelEvent> =
        listOf(ModelEvent.TextDelta(text), ModelEvent.Completed(Usage.ZERO))

    private class StaticModel(
        private val events: List<ModelEvent>,
    ) : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            events.forEach { emit(it) }
        }
    }

    private class ScriptedModel(
        scripts: List<List<ModelEvent>>,
    ) : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        private val remaining = ArrayDeque(scripts)

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            val events = if (remaining.isEmpty()) emptyList() else remaining.removeFirst()
            events.forEach { emit(it) }
        }
    }

    private class ParallelWorkerModel(
        private val expectedWorkers: Int,
    ) : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        private val mutex = Mutex()
        private val allStarted = CompletableDeferred<Unit>()
        var started: Int = 0
            private set

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            mutex.withLock {
                started++
                if (started == expectedWorkers) allStarted.complete(Unit)
            }
            allStarted.await()
            val prompt = request.messages.last().text
            if (prompt.startsWith("fail-")) {
                emit(ModelEvent.Failed(AgentError.ModelError("worker failed: $prompt", false)))
            } else {
                emit(ModelEvent.TextDelta("completed: $prompt"))
                emit(ModelEvent.Completed(Usage.ZERO))
            }
        }
    }
}
