package org.koaks.framework.skill

import kotlinx.serialization.Serializable
import org.koaks.framework.tool.Tool

const val SKILL_RESOURCE_TOOL_NAME: String = "read_skill_resource"

@Serializable
data class SkillResourceInput(
    val skill: String,
    val path: String,
    val line: Int? = null,
    val column: Int? = null,
    val limit: Int? = null,
)

internal class SkillResourceTool(
    private val providers: Map<SkillId, SkillResourceProvider>,
) : Tool<SkillResourceInput> {
    override val name: String = SKILL_RESOURCE_TOOL_NAME
    override val description: String = buildString {
        append("Read a UTF-8 text resource owned by an enabled Skill. ")
        append("Only relative paths inside the Skill directory are allowed. ")
        append("line and column form a 1-based cursor; limit is at most $MAX_RESOURCE_LINES. ")
        append("Enabled Skills with resources: ")
        append(providers.keys.joinToString(", ") { it.value })
    }
    override val inputSerializer = SkillResourceInput.serializer()

    override suspend fun execute(input: SkillResourceInput): String {
        val id = try {
            SkillId(input.skill)
        } catch (failure: IllegalArgumentException) {
            throw SkillException(
                stage = SkillStage.RESOURCE,
                message = "invalid Skill id '${input.skill}'",
                cause = failure,
            )
        }
        val provider = providers[id] ?: throw SkillException(
            stage = SkillStage.RESOURCE,
            skillId = id,
            message = "Skill '${id.value}' is not enabled or has no resources",
        )
        val request = try {
            SkillResourceRequest(
                path = input.path,
                cursor = SkillResourceCursor(
                    line = input.line ?: 1,
                    column = input.column ?: 1,
                ),
                maxLines = input.limit ?: DEFAULT_RESOURCE_LINES,
                maxChars = MAX_RESOURCE_OUTPUT_CHARS,
            )
        } catch (failure: IllegalArgumentException) {
            throw SkillException(
                stage = SkillStage.RESOURCE,
                skillId = id,
                message = failure.message ?: "invalid Skill resource request",
                cause = failure,
            )
        }
        val resource = provider.read(request)
        validateResourcePage(id, request, resource)
        return buildString {
            append(resource.path)
            append("  lines ")
            append(resource.firstLine)
            append('-')
            append(resource.lastLine)
            append(" of ")
            appendLine(resource.totalLines)
            append(resource.content)
            resource.nextCursor?.let { next ->
                append("\n[next cursor: line=")
                append(next.line)
                append(", column=")
                append(next.column)
                append(']')
            }
        }.trimEnd()
    }

    private fun validateResourcePage(
        id: SkillId,
        request: SkillResourceRequest,
        resource: SkillResource,
    ) {
        fun invalid(message: String): Nothing = throw SkillException(
            stage = SkillStage.RESOURCE,
            skillId = id,
            message = "invalid resource page for '${request.path}': $message",
        )

        if (resource.path != request.path) invalid("provider returned path '${resource.path}'")
        if (resource.content.length > request.maxChars) {
            invalid("content exceeds ${request.maxChars} characters")
        }
        if (resource.totalLines < 0) invalid("totalLines must not be negative")
        if (resource.firstLine != request.cursor.line) {
            invalid("firstLine ${resource.firstLine} does not match requested line ${request.cursor.line}")
        }
        if (resource.content.isEmpty()) {
            if (resource.lastLine != resource.firstLine - 1) {
                invalid("an empty page must end immediately before firstLine")
            }
        } else {
            if (resource.lastLine < resource.firstLine) invalid("lastLine precedes firstLine")
            if (resource.totalLines == 0 || resource.lastLine > resource.totalLines) {
                invalid("lastLine exceeds totalLines")
            }
            if (resource.lastLine - resource.firstLine + 1 > request.maxLines) {
                invalid("page exceeds ${request.maxLines} requested lines")
            }
        }
        resource.nextCursor?.let { next ->
            val current = request.cursor
            if (next.line < current.line || next.line == current.line && next.column <= current.column) {
                invalid("next cursor does not advance")
            }
            if (resource.totalLines == 0 || next.line > resource.totalLines) {
                invalid("next cursor exceeds totalLines")
            }
        }
    }
}

private const val DEFAULT_RESOURCE_LINES = 200
