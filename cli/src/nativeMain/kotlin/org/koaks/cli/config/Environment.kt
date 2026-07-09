@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal interface Environment {
    fun get(key: String): String?
}

internal object PosixEnvironment : Environment {
    override fun get(key: String): String? = getenv(key)?.toKString()
}

internal fun Environment.value(key: String): String? =
    get(key)?.trim()?.takeIf { it.isNotEmpty() }
