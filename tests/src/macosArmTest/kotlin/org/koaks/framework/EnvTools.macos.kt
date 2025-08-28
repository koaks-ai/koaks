@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import platform.posix.*
import kotlinx.cinterop.*

actual object EnvTools {

    private const val DIR = "../"
    private const val FILE_NAME = ".env"
    private val logger = KotlinLogging.logger {}

    actual fun loadValue(key: String): String {
        val filePath = DIR + FILE_NAME
        val file = fopen(filePath, "r")
        if (file == null) {
            logger.warn { "Env file not found at $filePath" }
            return ""
        }

        var value: String? = null
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(1024)
                while (fgets(buffer, 1024, file) != null) {
                    val line = buffer.toKString().trim()
                    if (line.isBlank() || line.startsWith("#")) continue
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val k = parts[0].trim()
                        val v = parts[1].trim()
                        if (k == key) {
                            value = v
                            break
                        }
                    }
                }
            }
        } finally {
            fclose(file)
        }

        if (value == null) {
            logger.warn { "Key '$key' not found in $filePath" }
            return ""
        }
        return value
    }
}
