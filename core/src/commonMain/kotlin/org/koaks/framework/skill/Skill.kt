package org.koaks.framework.skill

import kotlin.jvm.JvmInline
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.tool.Tool

/** Stable identifier used by loaders, the Agent DSL, and resource calls. */
@JvmInline
value class SkillId(val value: String) {
    init {
        require(SKILL_ID_REGEX.matches(value)) {
            "SkillId must match ${SKILL_ID_REGEX.pattern}: '$value'"
        }
    }

    override fun toString(): String = value
}

/** Lightweight metadata returned by [SkillLoader.discover]. */
data class SkillDescriptor(
    val id: SkillId,
    val description: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(description.isNotBlank()) { "skill '${id.value}' description must not be blank" }
    }
}

/** Position within a normalized UTF-8 text resource. Both values are 1-based. */
data class SkillResourceCursor(
    val line: Int,
    val column: Int,
) {
    init {
        require(line >= 1) { "resource cursor line must be 1 or greater" }
        require(column >= 1) { "resource cursor column must be 1 or greater" }
    }
}

/** Bounded request passed to a Skill-owned resource provider. */
data class SkillResourceRequest(
    val path: String,
    val cursor: SkillResourceCursor = SkillResourceCursor(line = 1, column = 1),
    val maxLines: Int = 200,
    val maxChars: Int = 30_000,
) {
    init {
        require(path.isNotBlank()) { "resource path must not be blank" }
        require(maxLines in 1..400) { "resource maxLines must be between 1 and 400" }
        require(maxChars >= 1) { "resource maxChars must be 1 or greater" }
    }
}

/** A bounded page read from a Skill-owned resource. */
data class SkillResource(
    val path: String,
    val content: String,
    val firstLine: Int,
    val lastLine: Int,
    val totalLines: Int,
    val nextCursor: SkillResourceCursor? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/** Lazy, read-only access to files or other resources owned by one Skill. */
fun interface SkillResourceProvider {
    suspend fun read(request: SkillResourceRequest): SkillResource
}

/** Fully loaded Skill contribution. */
data class SkillDefinition(
    val descriptor: SkillDescriptor,
    val instructions: String,
    val resources: SkillResourceProvider? = null,
    val tools: List<Tool<*>> = emptyList(),
)

/** Two-phase source: cheap metadata discovery followed by selected full loads. */
interface SkillLoader {
    suspend fun discover(): List<SkillDescriptor>
    suspend fun load(id: SkillId): SkillDefinition
}

/** Simple programmatic source for tests and applications that do not use SKILL.md. */
class InMemorySkillLoader(definitions: Iterable<SkillDefinition>) : SkillLoader {
    private val definitionsById: Map<SkillId, SkillDefinition>

    init {
        val indexed = LinkedHashMap<SkillId, SkillDefinition>()
        definitions.forEach { definition ->
            val previous = indexed.put(definition.descriptor.id, definition)
            require(previous == null) { "duplicate skill: ${definition.descriptor.id.value}" }
        }
        definitionsById = indexed
    }

    constructor(vararg definitions: SkillDefinition) : this(definitions.asIterable())

    override suspend fun discover(): List<SkillDescriptor> =
        definitionsById.values.map { it.descriptor }

    override suspend fun load(id: SkillId): SkillDefinition =
        definitionsById[id] ?: throw SkillException(
            stage = SkillStage.LOAD,
            skillId = id,
            message = "skill '${id.value}' was not found in the in-memory loader",
        )
}

enum class SkillStage {
    DISCOVER,
    LOAD,
    VALIDATE,
    RESOURCE,
}

/** Typed preparation/resource failure used by explicit [org.koaks.framework.loop.Agent.prepare]. */
class SkillException(
    val stage: SkillStage,
    val skillId: SkillId? = null,
    message: String,
    cause: Throwable? = null,
) : AgentFrameworkException(
    error = AgentError.SkillError(
        skillId = skillId,
        stage = stage,
        message = message,
        cause = cause,
    ),
    cause = cause,
)

private val SKILL_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
