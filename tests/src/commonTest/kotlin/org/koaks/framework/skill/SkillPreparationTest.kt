package org.koaks.framework.skill

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import org.koaks.framework.tool.InlineTool
import org.koaks.framework.tool.LazyToolSource
import org.koaks.framework.tool.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillPreparationTest {
    @Test
    fun no_use_loads_all_by_source_then_id_and_composes_instructions() = runTest {
        val first = RecordingLoader(
            skill("zeta", "ZETA"),
            skill("alpha", "ALPHA"),
        )
        val second = RecordingLoader(skill("beta", "BETA"))
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed(Usage.ZERO)),
        )
        val agent = agent {
            id = "skills-all"
            instructions = "BASE"
            model { custom(model) }
            skills {
                source(first)
                source(second)
            }
        }

        agent.run("hello")

        assertEquals(listOf("alpha", "zeta"), first.loaded)
        assertEquals(listOf("beta"), second.loaded)
        assertEquals(listOf("alpha", "zeta", "beta"), agent.skillDescriptors.map { it.id.value })
        val system = model.lastRequest!!.messages.first().text
        assertTrue(system.indexOf("BASE") < system.indexOf("ALPHA"))
        assertTrue(system.indexOf("ALPHA") < system.indexOf("ZETA"))
        assertTrue(system.indexOf("ZETA") < system.indexOf("BETA"))
    }

    @Test
    fun use_is_a_global_allow_list_and_preserves_use_order() = runTest {
        val first = RecordingLoader(skill("one", "ONE"), skill("two", "TWO"))
        val second = RecordingLoader(skill("three", "THREE"))
        val agent = agent {
            id = "skills-selected"
            model { custom(FakeLanguageModel()) }
            skills {
                use("three")
                source(first)
                use("one")
                source(second)
            }
        }

        agent.prepare()

        assertEquals(listOf("one"), first.loaded)
        assertEquals(listOf("three"), second.loaded)
        assertEquals(listOf("three", "one"), agent.skillDescriptors.map { it.id.value })
    }

    @Test
    fun selected_unknown_skill_fails_before_model_call() = runTest {
        val model = FakeLanguageModel()
        val agent = agent {
            id = "skills-unknown"
            model { custom(model) }
            skills {
                source(RecordingLoader(skill("known", "KNOWN")))
                use("missing")
            }
        }

        val failure = assertFailsWith<SkillException> { agent.prepare() }

        assertEquals(SkillStage.VALIDATE, failure.stage)
        assertEquals("missing", failure.skillId?.value)
        assertEquals(0, model.calls)
    }

    @Test
    fun duplicate_ids_across_sources_are_rejected() = runTest {
        val agent = agent {
            id = "skills-duplicate"
            model { custom(FakeLanguageModel()) }
            skills {
                source(RecordingLoader(skill("same", "ONE")))
                source(RecordingLoader(skill("same", "TWO")))
            }
        }

        val failure = assertFailsWith<SkillException> { agent.prepare() }
        assertEquals(SkillStage.VALIDATE, failure.stage)
    }

    @Test
    fun duplicate_unselected_ids_do_not_block_selected_skill() = runTest {
        val first = RecordingLoader(skill("same", "ONE"), skill("selected", "SELECTED"))
        val second = RecordingLoader(skill("same", "TWO"))
        val agent = agent {
            id = "skills-unselected-duplicate"
            model { custom(FakeLanguageModel()) }
            skills {
                source(first)
                source(second)
                use("selected")
            }
        }

        agent.prepare()

        assertEquals(listOf("selected"), first.loaded)
        assertTrue(second.loaded.isEmpty())
    }

    @Test
    fun concurrent_prepare_discovers_and_loads_once() = runTest {
        val loader = RecordingLoader(skill("shared", "SHARED"), delayMillis = 10)
        val agent = agent {
            id = "skills-concurrent"
            model { custom(FakeLanguageModel()) }
            skills { source(loader) }
        }

        coroutineScope {
            List(12) { async { agent.prepare() } }.awaitAll()
        }

        assertEquals(1, loader.discoverCalls)
        assertEquals(listOf("shared"), loader.loaded)
    }

    @Test
    fun concurrent_failed_prepare_is_shared_without_reloading() = runTest {
        val loader = FailingLoader(delayMillis = 10)
        val agent = agent {
            id = "skills-concurrent-failure"
            model { custom(FakeLanguageModel()) }
            skills { source(loader) }
        }

        val failures = coroutineScope {
            List(12) {
                async { runCatching { agent.prepare() }.exceptionOrNull() }
            }.awaitAll()
        }

        assertEquals(1, loader.discoverCalls)
        assertTrue(failures.all { it is SkillException && it.stage == SkillStage.DISCOVER })
    }

    @Test
    fun cancelled_prepare_can_be_retried() = runTest {
        val loader = CancelOnceLoader(skill("retry", "RETRY"))
        val agent = agent {
            id = "skills-cancel-retry"
            model { custom(FakeLanguageModel()) }
            skills { source(loader) }
        }

        assertFailsWith<CancellationException> { agent.prepare() }
        agent.prepare()

        assertEquals(2, loader.discoverCalls)
        assertEquals(listOf("retry"), agent.skillDescriptors.map { it.id.value })
    }

    @Test
    fun skill_tool_conflict_is_rejected_atomically() = runTest {
        val duplicate = InlineTool(
            name = "duplicate",
            description = "skill duplicate",
            inputSerializer = NoArgs.serializer(),
        ) { "skill" }
        val definition = skill("tools", "TOOLS").copy(tools = listOf(duplicate))
        val agent = agent {
            id = "skills-tool-conflict"
            model { custom(FakeLanguageModel()) }
            tools {
                tool<NoArgs>("duplicate", "base duplicate") { "base" }
            }
            skills { source(InMemorySkillLoader(definition)) }
        }

        val failure = assertFailsWith<AgentFrameworkException> { agent.prepare() }

        val error = assertIs<AgentError.PreparationError>(failure.error)
        assertEquals("tools", error.component)
        assertEquals(setOf("duplicate"), agent.tools.names())
    }

    @Test
    fun automatic_preparation_failure_is_returned_without_calling_model() = runTest {
        val model = FakeLanguageModel()
        val agent = agent {
            id = "skills-runtime-failure"
            model { custom(model) }
            skills {
                source(RecordingLoader(skill("known", "KNOWN")))
                use("missing")
            }
        }

        val result = assertIs<AgentResult.Failed>(agent.run("hello"))
        val events = agent.stream("hello again").toList()

        assertIs<AgentError.SkillError>(result.error)
        assertIs<AgentError.SkillError>(assertIs<AgentEvent.Failed>(events.single()).error)
        assertEquals(0, model.calls)
    }

    @Test
    fun no_skills_resolves_lazy_tools_into_fixed_catalog() = runTest {
        val lazyTool = InlineTool(
            name = "lazy",
            description = "lazy tool",
            inputSerializer = NoArgs.serializer(),
        ) { "lazy" }
        val agent = agent {
            id = "no-skills-fixed-tools"
            model { custom(FakeLanguageModel()) }
            tools { source(LazyToolSource { listOf(lazyTool) }) }
        }

        agent.run("hello")

        assertEquals(setOf("lazy"), agent.tools.names())
    }

    @Test
    fun resource_tool_is_registered_and_reads_enabled_skill() = runTest {
        val provider = SkillResourceProvider { request ->
            SkillResource(
                request.path,
                "hello",
                request.cursor.line,
                request.cursor.line,
                totalLines = 1,
            )
        }
        val definition = skill("docs", "DOCS").copy(resources = provider)
        val agent = agent {
            id = "skills-resource-tool"
            model { custom(FakeLanguageModel()) }
            skills { source(InMemorySkillLoader(definition)) }
        }

        agent.prepare()
        val outcome = agent.tools.call(
            SKILL_RESOURCE_TOOL_NAME,
            """{"skill":"docs","path":"guide.md"}""",
        )

        val success = assertIs<ToolOutcome.Success>(outcome)
        assertTrue(success.output.contains("hello"))
    }

    @Test
    fun resource_tool_rejects_custom_provider_output_over_limit() = runTest {
        val provider = SkillResourceProvider { request ->
            SkillResource(
                request.path,
                "x".repeat(request.maxChars + 1),
                request.cursor.line,
                request.cursor.line,
                totalLines = 1,
            )
        }
        val agent = agent {
            id = "skills-resource-limit"
            model { custom(FakeLanguageModel()) }
            skills {
                source(InMemorySkillLoader(skill("docs", "DOCS").copy(resources = provider)))
            }
        }

        agent.prepare()
        val outcome = agent.tools.call(
            SKILL_RESOURCE_TOOL_NAME,
            """{"skill":"docs","path":"guide.md"}""",
        )

        assertIs<AgentError.SkillError>(assertIs<ToolOutcome.Failure>(outcome).error)
    }
}

private class RecordingLoader(
    vararg definitions: SkillDefinition,
    private val delayMillis: Long = 0,
) : SkillLoader {
    private val byId = definitions.associateBy { it.descriptor.id }
    var discoverCalls: Int = 0
    val loaded = mutableListOf<String>()

    override suspend fun discover(): List<SkillDescriptor> {
        discoverCalls++
        if (delayMillis > 0) delay(delayMillis)
        return byId.values.map { it.descriptor }
    }

    override suspend fun load(id: SkillId): SkillDefinition {
        loaded += id.value
        return byId.getValue(id)
    }
}

private class FailingLoader(private val delayMillis: Long) : SkillLoader {
    var discoverCalls: Int = 0

    override suspend fun discover(): List<SkillDescriptor> {
        discoverCalls++
        delay(delayMillis)
        throw SkillException(SkillStage.DISCOVER, message = "discovery failed")
    }

    override suspend fun load(id: SkillId): SkillDefinition = error("load must not be called")
}

private class CancelOnceLoader(private val definition: SkillDefinition) : SkillLoader {
    var discoverCalls: Int = 0

    override suspend fun discover(): List<SkillDescriptor> {
        discoverCalls++
        if (discoverCalls == 1) throw CancellationException("cancel once")
        return listOf(definition.descriptor)
    }

    override suspend fun load(id: SkillId): SkillDefinition = definition
}

private fun skill(id: String, instructions: String): SkillDefinition = SkillDefinition(
    descriptor = SkillDescriptor(SkillId(id), "description for $id"),
    instructions = instructions,
)
