package org.koaks.framework.skill

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath

/**
 * Built-in loader for `<root>/<skill-id>/SKILL.md`.
 *
 * The default system filesystem is available on JVM, Native, and Node.js. Browser
 * callers should use a custom [SkillLoader] backed by application-owned storage.
 */
class MarkdownDirectorySkillLoader internal constructor(
    private val root: String,
    explicitFileSystem: SkillFileSystem?,
) : SkillLoader {
    constructor(root: String) : this(root, null)

    init {
        require(root.isNotBlank()) { "skill source path must not be blank" }
    }

    private val fileSystemMutex = Mutex()
    private var resolvedFileSystem = explicitFileSystem
    private var fileSystemResolved = explicitFileSystem != null

    private suspend fun requireFileSystem(): SkillFileSystem = fileSystemMutex.withLock {
        if (!fileSystemResolved) {
            resolvedFileSystem = defaultSkillFileSystem()
            fileSystemResolved = true
        }
        resolvedFileSystem ?: throw SkillException(
            stage = SkillStage.DISCOVER,
            message = "Markdown directory Skill sources are unavailable on this platform; " +
                "use source(loader) with a custom SkillLoader",
        )
    }

    private val indexMutex = Mutex()
    private var cachedIndex: Map<SkillId, IndexedSkill>? = null

    override suspend fun discover(): List<SkillDescriptor> =
        index().values.map { it.descriptor }

    override suspend fun load(id: SkillId): SkillDefinition {
        val indexed = index()[id] ?: throw SkillException(
            stage = SkillStage.LOAD,
            skillId = id,
            message = "skill '${id.value}' was not found under '$root'",
        )
        val fileSystem = requireFileSystem()

        return try {
            val metadata = fileSystem.metadata(indexed.skillFile)
            val size = metadata.size
            if (!metadata.isRegularFile || size == null || size > MAX_SKILL_FILE_BYTES) {
                throw SkillException(
                    stage = SkillStage.LOAD,
                    skillId = id,
                    message = "${indexed.skillFile} must be a UTF-8 file no larger than $MAX_SKILL_FILE_BYTES bytes",
                )
            }
            val document = fileSystem.readUtf8(indexed.skillFile)
            val parsed = parseDocument(document, indexed.skillFile.toString(), includeBody = true)
            if (parsed.descriptor.id != id) {
                throw SkillException(
                    stage = SkillStage.VALIDATE,
                    skillId = id,
                    message = "SKILL.md name '${parsed.descriptor.id.value}' does not match directory '${id.value}'",
                )
            }
            SkillDefinition(
                descriptor = parsed.descriptor,
                instructions = parsed.body,
                resources = DirectorySkillResourceProvider(id, indexed.skillRoot, fileSystem),
            )
        } catch (skill: SkillException) {
            throw skill
        } catch (failure: Throwable) {
            throw SkillException(
                stage = SkillStage.LOAD,
                skillId = id,
                message = "failed to load skill '${id.value}' from ${indexed.skillFile}: " +
                    (failure.message ?: "unknown error"),
                cause = failure,
            )
        }
    }

    private suspend fun index(): Map<SkillId, IndexedSkill> {
        cachedIndex?.let { return it }
        return indexMutex.withLock {
            cachedIndex?.let { return@withLock it }
            val scanned = scanDirectory()
            cachedIndex = scanned
            scanned
        }
    }

    private suspend fun scanDirectory(): Map<SkillId, IndexedSkill> {
        val fileSystem = requireFileSystem()
        try {
            val canonicalRoot = fileSystem.canonicalize(root.toPath())
            val rootMetadata = fileSystem.metadata(canonicalRoot)
            if (!rootMetadata.isDirectory) {
                throw SkillException(
                    stage = SkillStage.DISCOVER,
                    message = "skill source '$root' is not a directory",
                )
            }

            val result = LinkedHashMap<SkillId, IndexedSkill>()
            fileSystem.list(canonicalRoot).forEach { child ->
                val childMetadata = fileSystem.metadata(child)
                if (childMetadata.isSymbolicLink) {
                    throw SkillException(
                        stage = SkillStage.DISCOVER,
                        message = "symbolic links are not allowed directly under skill source '$root': $child",
                    )
                }
                if (!childMetadata.isDirectory) return@forEach

                val directoryId = try {
                    SkillId(child.name)
                } catch (invalid: IllegalArgumentException) {
                    throw SkillException(
                        stage = SkillStage.DISCOVER,
                        message = "invalid skill directory name '${child.name}' under '$root'",
                        cause = invalid,
                    )
                }
                val skillFile = child / SKILL_FILE_NAME
                val skillMetadata = fileSystem.metadataOrNull(skillFile) ?: throw SkillException(
                    stage = SkillStage.DISCOVER,
                    skillId = directoryId,
                    message = "skill directory '$child' does not contain $SKILL_FILE_NAME",
                )
                if (!skillMetadata.isRegularFile || skillMetadata.isSymbolicLink) {
                    throw SkillException(
                        stage = SkillStage.DISCOVER,
                        skillId = directoryId,
                        message = "$skillFile must be a regular file and not a symbolic link",
                    )
                }

                val frontMatter = readFrontMatter(fileSystem, skillFile, directoryId)
                val descriptor = parseFrontMatter(frontMatter, skillFile.toString())
                if (descriptor.id != directoryId) {
                    throw SkillException(
                        stage = SkillStage.VALIDATE,
                        skillId = directoryId,
                        message = "SKILL.md name '${descriptor.id.value}' does not match directory '${directoryId.value}'",
                    )
                }
                result[directoryId] = IndexedSkill(child, skillFile, descriptor)
            }
            return result
        } catch (skill: SkillException) {
            throw skill
        } catch (failure: Throwable) {
            throw SkillException(
                stage = SkillStage.DISCOVER,
                message = "failed to discover skills under '$root': ${failure.message ?: "unknown error"}",
                cause = failure,
            )
        }
    }

    private fun readFrontMatter(fileSystem: SkillFileSystem, file: Path, id: SkillId): String {
        val lines = fileSystem.readUtf8(file).lineSequence().iterator()
        val first = lines.nextOrNull()?.removePrefix("\uFEFF")
        if (first?.trim() != FRONT_MATTER_DELIMITER) {
            throw SkillException(
                stage = SkillStage.DISCOVER,
                skillId = id,
                message = "$file must start with YAML front matter",
            )
        }

        val yaml = StringBuilder()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.trim() == FRONT_MATTER_DELIMITER) return yaml.toString()
            yaml.appendLine(line)
            if (yaml.length > MAX_FRONT_MATTER_CHARS) {
                throw SkillException(
                    stage = SkillStage.DISCOVER,
                    skillId = id,
                    message = "$file front matter exceeds $MAX_FRONT_MATTER_CHARS characters",
                )
            }
        }
        throw SkillException(
            stage = SkillStage.DISCOVER,
            skillId = id,
            message = "$file has unclosed YAML front matter",
        )
    }
}

private class DirectorySkillResourceProvider(
    private val skillId: SkillId,
    private val skillRoot: Path,
    private val fileSystem: SkillFileSystem,
) : SkillResourceProvider {
    override suspend fun read(request: SkillResourceRequest): SkillResource {
        val path = request.path

        val relative = try {
            path.toPath()
        } catch (failure: Throwable) {
            throw resourceError("invalid resource path '$path'", failure)
        }
        if (path.isBlank() || relative.isAbsolute || relative.volumeLetter != null ||
            relative.segments.any { it == ".." }
        ) {
            throw resourceError("resource path must be relative and must not contain '..': '$path'")
        }
        if (relative.name == SKILL_FILE_NAME) {
            throw resourceError("$SKILL_FILE_NAME is instructions, not a Skill resource")
        }

        try {
            val canonicalRoot = fileSystem.canonicalize(skillRoot)
            val canonicalResource = fileSystem.canonicalize(skillRoot.resolve(relative))
            if (!canonicalResource.isInside(canonicalRoot)) {
                throw resourceError("resource path escapes the Skill directory: '$path'")
            }
            val metadata = fileSystem.metadata(canonicalResource)
            val size = metadata.size
            if (!metadata.isRegularFile || size == null) {
                throw resourceError("resource is not a regular file: '$path'")
            }
            if (size > MAX_RESOURCE_FILE_BYTES) {
                throw resourceError("resource '$path' exceeds $MAX_RESOURCE_FILE_BYTES bytes")
            }

            val text = fileSystem.readUtf8(canonicalResource)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            return pageResource(text, request)
        } catch (skill: SkillException) {
            throw skill
        } catch (failure: Throwable) {
            throw resourceError("failed to read resource '$path': ${failure.message ?: "unknown error"}", failure)
        }
    }

    private fun resourceError(message: String, cause: Throwable? = null): SkillException =
        SkillException(SkillStage.RESOURCE, skillId = skillId, message = message, cause = cause)

    private fun pageResource(text: String, request: SkillResourceRequest): SkillResource {
        if (text.isEmpty()) {
            if (request.cursor != SkillResourceCursor(1, 1)) {
                throw resourceError("resource cursor is outside the empty resource")
            }
            return SkillResource(request.path, "", 1, 0, 0)
        }

        val lineStarts = buildList {
            add(0)
            text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        val lineIndex = request.cursor.line - 1
        if (lineIndex !in lineStarts.indices) {
            throw resourceError("line ${request.cursor.line} exceeds ${lineStarts.size} total lines")
        }
        val lineStart = lineStarts[lineIndex]
        val lineEnd = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] - 1 else text.length
        val maxColumn = lineEnd - lineStart + 1
        if (request.cursor.column !in 1..maxColumn) {
            throw resourceError(
                "column ${request.cursor.column} exceeds line ${request.cursor.line} length ${maxColumn - 1}",
            )
        }

        val absoluteStart = lineStart + request.cursor.column - 1
        val lineBoundary = lineStarts.getOrNull(lineIndex + request.maxLines) ?: text.length
        var absoluteEnd = minOf(text.length, lineBoundary, absoluteStart + request.maxChars)
        if (absoluteEnd < text.length && absoluteEnd > absoluteStart &&
            text[absoluteEnd].isLowSurrogate() && text[absoluteEnd - 1].isHighSurrogate()
        ) {
            absoluteEnd--
        }
        if (absoluteEnd == absoluteStart && absoluteStart < text.length) {
            absoluteEnd = minOf(text.length, absoluteStart + 1)
        }

        val content = text.substring(absoluteStart, absoluteEnd)
        val nextCursor = absoluteEnd.takeIf { it < text.length }?.let { offsetToCursor(it, lineStarts) }
        val lastLine = if (content.isEmpty()) {
            request.cursor.line - 1
        } else {
            offsetToCursor(absoluteEnd - 1, lineStarts).line
        }
        return SkillResource(
            path = request.path,
            content = content,
            firstLine = request.cursor.line,
            lastLine = lastLine,
            totalLines = lineStarts.size,
            nextCursor = nextCursor,
        )
    }

    private fun offsetToCursor(offset: Int, lineStarts: List<Int>): SkillResourceCursor {
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= offset) low = middle + 1 else high = middle - 1
        }
        val lineIndex = high.coerceAtLeast(0)
        return SkillResourceCursor(
            line = lineIndex + 1,
            column = offset - lineStarts[lineIndex] + 1,
        )
    }
}

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null

private fun parseDocument(text: String, source: String, includeBody: Boolean): ParsedSkillDocument {
    val firstStart = if (text.startsWith('\uFEFF')) 1 else 0
    val firstEnd = text.indexOf('\n', firstStart).let { if (it < 0) text.length else it }
    val firstLine = text.substring(firstStart, firstEnd).trimEnd('\r').trim()
    if (firstLine != FRONT_MATTER_DELIMITER) {
        throw SkillException(SkillStage.LOAD, message = "$source must start with YAML front matter")
    }

    val yamlStart = if (firstEnd < text.length) firstEnd + 1 else text.length
    var lineStart = yamlStart
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd).trimEnd('\r')
        if (line.trim() == FRONT_MATTER_DELIMITER) {
            val yamlText = text.substring(yamlStart, lineStart)
            val bodyStart = if (lineEnd < text.length) lineEnd + 1 else text.length
            return ParsedSkillDocument(
                descriptor = parseFrontMatter(yamlText, source),
                body = if (includeBody) text.substring(bodyStart) else "",
            )
        }
        if (lineEnd == text.length) break
        lineStart = lineEnd + 1
    }
    throw SkillException(SkillStage.LOAD, message = "$source has unclosed YAML front matter")
}

private fun parseFrontMatter(text: String, source: String): SkillDescriptor {
    val map = try {
        Yaml.default.parseToYamlNode(text) as? YamlMap
            ?: throw SkillException(SkillStage.VALIDATE, message = "$source front matter must be a YAML map")
    } catch (skill: SkillException) {
        throw skill
    } catch (failure: Throwable) {
        throw SkillException(
            stage = SkillStage.VALIDATE,
            message = "invalid YAML front matter in $source: ${failure.message ?: "unknown error"}",
            cause = failure,
        )
    }

    val name = requiredScalar(map, "name", source)
    val description = requiredScalar(map, "description", source)
    val metadata = map.entries.mapNotNull { (key, value) ->
        if (key.content == "name" || key.content == "description") null
        else key.content to value.metadataString()
    }.toMap()

    return try {
        SkillDescriptor(SkillId(name), description, metadata)
    } catch (failure: IllegalArgumentException) {
        throw SkillException(
            stage = SkillStage.VALIDATE,
            message = "invalid front matter in $source: ${failure.message}",
            cause = failure,
        )
    }
}

private fun requiredScalar(map: YamlMap, key: String, source: String): String {
    val value = try {
        map.getScalar(key)?.content
    } catch (failure: Throwable) {
        throw SkillException(SkillStage.VALIDATE, message = "$source field '$key' must be a scalar", cause = failure)
    }?.trim()
    if (value.isNullOrBlank()) {
        throw SkillException(SkillStage.VALIDATE, message = "$source requires non-blank '$key' front matter")
    }
    return value
}

private fun YamlNode.metadataString(): String = when (this) {
    is YamlScalar -> content
    else -> contentToString()
}

private fun Path.isInside(root: Path): Boolean =
    this.root == root.root && segments.size >= root.segments.size &&
        segments.take(root.segments.size) == root.segments

private data class IndexedSkill(
    val skillRoot: Path,
    val skillFile: Path,
    val descriptor: SkillDescriptor,
)

private data class ParsedSkillDocument(
    val descriptor: SkillDescriptor,
    val body: String,
)

private const val SKILL_FILE_NAME = "SKILL.md"
private const val FRONT_MATTER_DELIMITER = "---"
private const val MAX_FRONT_MATTER_CHARS = 64 * 1024
private const val MAX_SKILL_FILE_BYTES = 1_000_000L
private const val MAX_RESOURCE_FILE_BYTES = 1_000_000L
internal const val MAX_RESOURCE_LINES = 400
internal const val MAX_RESOURCE_OUTPUT_CHARS = 30_000
