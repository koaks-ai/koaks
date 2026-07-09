@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs

internal object ConfigFileLoader {
    fun load(env: Environment): FileConfig {
        val path = configPath(env) ?: throw CliException("Unable to find home directory for ~/.koaks/config.toml.")
        val text = readText(path) ?: run {
            createDefaultConfig(path)
            readText(path) ?: throw CliException("Unable to read config file after creating it: $path")
        }
        return TomlConfigParser.parse(text, path)
    }

    fun configPath(env: Environment): String? {
        val home = env.value("HOME") ?: env.value("USERPROFILE") ?: return null
        return "${home.trimEnd('/', '\\')}/.koaks/config.toml"
    }

    internal fun defaultConfigText(): String =
        """
        # Koaks CLI configuration.
        # Add api_key under a provider before using remote models.
        provider = "openai"

        [providers.openai]
        base_url = "${Provider.OPENAI.defaultBaseUrl}"
        api_key = "sk-..."
        model = "gpt-5.5"
        model_list = ["gpt-5.5"]

        [providers.anthropic]
        base_url = "${Provider.ANTHROPIC.defaultBaseUrl}"
        api_key = "sk-ant-..."
        model = "claude-opus-4-8"
        model_list = ["claude-opus-4-8"]
        """.trimIndent() + "\n"

    private fun createDefaultConfig(path: String) {
        parentDirectory(path)?.let(ConfigFileSystem::createDirectory)
        writeText(path, defaultConfigText())
    }

    private fun readText(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        val text = StringBuilder()
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(CONFIG_BUFFER_SIZE)
                while (fgets(buffer, CONFIG_BUFFER_SIZE, file) != null) {
                    text.append(buffer.toKString())
                }
            }
        } finally {
            fclose(file)
        }
        return text.toString()
    }

    private fun writeText(path: String, text: String) {
        val file = fopen(path, "wb")
            ?: throw CliException("Unable to create config file: $path")

        try {
            if (fputs(text, file) < 0) {
                throw CliException("Unable to write config file: $path")
            }
        } finally {
            fclose(file)
        }
    }

    private fun parentDirectory(path: String): String? {
        val slashIndex = path.lastIndexOf('/')
        val backslashIndex = path.lastIndexOf('\\')
        val index = maxOf(slashIndex, backslashIndex)
        if (index <= 0) return null
        return path.take(index).takeIf { it.isNotBlank() }
    }
}

internal expect object ConfigFileSystem {
    fun createDirectory(path: String): Boolean
}

private const val CONFIG_BUFFER_SIZE = 8192
