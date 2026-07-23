package org.koaks.framework.skill

import okio.FileSystem
import okio.Path

internal data class SkillFileMetadata(
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val size: Long?,
    val isSymbolicLink: Boolean,
)

internal interface SkillFileSystem {
    fun canonicalize(path: Path): Path
    fun metadataOrNull(path: Path): SkillFileMetadata?
    fun list(path: Path): List<Path>
    fun readUtf8(path: Path): String
}

internal class OkioSkillFileSystem(private val delegate: FileSystem) : SkillFileSystem {
    override fun canonicalize(path: Path): Path = delegate.canonicalize(path)

    override fun metadataOrNull(path: Path): SkillFileMetadata? = delegate.metadataOrNull(path)?.let { metadata ->
        SkillFileMetadata(
            isDirectory = metadata.isDirectory,
            isRegularFile = metadata.isRegularFile,
            size = metadata.size,
            isSymbolicLink = metadata.symlinkTarget != null,
        )
    }

    override fun list(path: Path): List<Path> = delegate.list(path)

    override fun readUtf8(path: Path): String = delegate.read(path) { readUtf8() }
}

internal fun SkillFileSystem.metadata(path: Path): SkillFileMetadata =
    metadataOrNull(path) ?: throw IllegalStateException("file does not exist: $path")

internal expect suspend fun defaultSkillFileSystem(): SkillFileSystem?
