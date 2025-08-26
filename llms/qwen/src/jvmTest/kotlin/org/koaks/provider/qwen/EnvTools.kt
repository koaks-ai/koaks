package org.koaks.provider.qwen

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines


object EnvTools {

    private val envMap: Map<String, String> by lazy { loadEnvFile() }

    fun loadValue(key: String): String =
        envMap[key] ?: error("Key '$key' not found in .env file")

    private fun loadEnvFile(): Map<String, String> {
        val envFile = findEnvFile() ?: error(".env file not found in project root")
        return envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index > 0) {
                    val key = line.take(index).trim()
                    val value = line.substring(index + 1).trim().removeSurrounding("\"")
                    key to value
                } else null
            }.toMap()
    }

    private fun findEnvFile(): java.nio.file.Path? {
        var dir = Path(System.getProperty("user.dir"))
        while (true) {
            val settingsFile = dir.resolve("settings.gradle.kts")
            if (settingsFile.exists() && settingsFile.isRegularFile()) {
                val envFile = dir.resolve(".env")
                return if (envFile.exists() && envFile.isRegularFile()) envFile else null
            }
            dir = dir.parent ?: break
        }
        return null
    }
}
