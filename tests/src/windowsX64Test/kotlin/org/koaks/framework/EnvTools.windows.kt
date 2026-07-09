@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

private val logger = KotlinLogging.logger {}

actual object EnvTools {
    actual fun loadValue(key: String): String {
        return getenv(key)?.toKString() ?: run {
            logger.warn { "No value found for key $key" }
            ""
        }
    }
}
