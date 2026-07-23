package org.koaks.framework.skill

import kotlinx.coroutines.await
import okio.Path
import okio.Path.Companion.toPath
import kotlin.js.Promise

internal actual suspend fun defaultSkillFileSystem(): SkillFileSystem? =
    if (isNodeRuntime()) NodeSkillFileSystem.create() else null

private fun isNodeRuntime(): Boolean = js(
    "typeof process !== 'undefined' && process.versions != null && process.versions.node != null",
) as Boolean

private class NodeSkillFileSystem(private val fs: dynamic) : SkillFileSystem {

    override fun canonicalize(path: Path): Path =
        (fs.realpathSync(path.toString()) as String).toPath()

    override fun metadataOrNull(path: Path): SkillFileMetadata? = try {
        val stat = fs.lstatSync(path.toString())
        SkillFileMetadata(
            isDirectory = stat.isDirectory() as Boolean,
            isRegularFile = stat.isFile() as Boolean,
            size = (stat.size as Number).toLong(),
            isSymbolicLink = stat.isSymbolicLink() as Boolean,
        )
    } catch (failure: Throwable) {
        if (failure.asDynamic().code == "ENOENT") null else throw failure
    }

    override fun list(path: Path): List<Path> {
        val names = fs.readdirSync(path.toString()) as Array<String>
        return names.map { name -> path / name }
    }

    override fun readUtf8(path: Path): String = fs.readFileSync(path.toString(), "utf8") as String

    companion object {
        suspend fun create(): NodeSkillFileSystem {
            val importer: dynamic = js("new Function('specifier', 'return import(specifier)')")
            val fs = (importer("node:fs") as Promise<dynamic>).await()
            return NodeSkillFileSystem(fs)
        }
    }
}
