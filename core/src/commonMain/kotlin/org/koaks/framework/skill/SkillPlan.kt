package org.koaks.framework.skill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.tool.Tool

/** DSL scope used by `agent { skills { ... } }`. */
@AgentDSL
class SkillsScope {
    private val loaders = mutableListOf<SkillLoader>()
    private val selected = mutableListOf<SkillId>()

    /** Adds the built-in directory loader for child folders containing `SKILL.md`. */
    fun source(path: String) {
        require(path.isNotBlank()) { "skill source path must not be blank" }
        loaders += MarkdownDirectorySkillLoader(path)
    }

    /** Adds an application-defined loader. */
    fun source(loader: SkillLoader) {
        loaders += loader
    }

    /** Switches selection to global allow-list mode and preserves call order. */
    fun use(id: String) {
        val skillId = SkillId(id)
        require(skillId !in selected) { "duplicate skill selection: $id" }
        selected += skillId
    }

    internal fun build(): SkillPlan {
        require(loaders.isNotEmpty()) { "skills { } requires at least one source(...)" }
        return SkillPlan(loaders.toList(), selected.toList())
    }
}

internal class SkillPlan(
    private val loaders: List<SkillLoader>,
    private val selected: List<SkillId>,
) {
    suspend fun prepare(): PreparedSkills {
        val discoveries = coroutineScope {
            loaders.mapIndexed { index, loader ->
                async { discover(index, loader) }
            }.awaitAll()
        }

        val byId = LinkedHashMap<SkillId, MutableList<LocatedSkill>>()
        discoveries.flatten().forEach { located ->
            byId.getOrPut(located.descriptor.id) { mutableListOf() } += located
        }

        val toLoad = if (selected.isEmpty()) {
            byId.values.firstOrNull { it.size > 1 }?.let { throwAmbiguousSkill(it) }
            discoveries.flatMap { sourceSkills -> sourceSkills.sortedBy { it.descriptor.id.value } }
        } else {
            selected.map { id ->
                val candidates = byId[id].orEmpty()
                when (candidates.size) {
                    0 -> throw SkillException(
                        stage = SkillStage.VALIDATE,
                        skillId = id,
                        message = "selected skill '${id.value}' was not discovered by any source",
                    )
                    1 -> candidates.single()
                    else -> throwAmbiguousSkill(candidates)
                }
            }
        }

        // Loading is deliberately ordered: custom loaders may observe calls, and the
        // selected order is also the merge order for instructions and tools.
        val definitions = toLoad.map { located -> load(located) }

        return PreparedSkills(definitions)
    }

    private suspend fun discover(index: Int, loader: SkillLoader): List<LocatedSkill> {
        val descriptors = try {
            loader.discover()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (skill: SkillException) {
            throw skill
        } catch (failure: Throwable) {
            throw SkillException(
                stage = SkillStage.DISCOVER,
                message = "skill source ${index + 1} discovery failed: ${failure.message ?: "unknown error"}",
                cause = failure,
            )
        }

        return descriptors.map { descriptor -> LocatedSkill(index, loader, descriptor) }
    }

    private suspend fun load(located: LocatedSkill): SkillDefinition {
        val definition = try {
            located.loader.load(located.descriptor.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (skill: SkillException) {
            throw skill
        } catch (failure: Throwable) {
            throw SkillException(
                stage = SkillStage.LOAD,
                skillId = located.descriptor.id,
                message = "skill '${located.descriptor.id.value}' load failed: ${failure.message ?: "unknown error"}",
                cause = failure,
            )
        }

        if (definition.descriptor != located.descriptor) {
            throw SkillException(
                stage = SkillStage.VALIDATE,
                skillId = located.descriptor.id,
                message = "skill '${located.descriptor.id.value}' descriptor changed between discovery and load",
            )
        }
        return definition
    }

    private fun throwAmbiguousSkill(candidates: List<LocatedSkill>): Nothing {
        val id = candidates.first().descriptor.id
        throw SkillException(
            stage = SkillStage.VALIDATE,
            skillId = id,
            message = "skill '${id.value}' is ambiguous across sources " +
                candidates.joinToString(", ") { (it.sourceIndex + 1).toString() },
        )
    }
}

internal data class PreparedSkills(val definitions: List<SkillDefinition>) {
    val descriptors: List<SkillDescriptor> get() = definitions.map { it.descriptor }
    val tools: List<Tool<*>> get() = definitions.flatMap { it.tools }
    val instructionSegments: List<String> get() = definitions.mapNotNull { definition ->
        definition.instructions.takeIf(String::isNotBlank)?.let { instructions ->
            buildString {
                append("## Skill: ")
                appendLine(definition.descriptor.id.value)
                append("Description: ")
                appendLine(definition.descriptor.description)
                appendLine()
                append(instructions.trim())
            }
        }
    }
    val resourceProviders: Map<SkillId, SkillResourceProvider> = definitions.mapNotNull { definition ->
        definition.resources?.let { definition.descriptor.id to it }
    }.toMap()
}

private data class LocatedSkill(
    val sourceIndex: Int,
    val loader: SkillLoader,
    val descriptor: SkillDescriptor,
)
