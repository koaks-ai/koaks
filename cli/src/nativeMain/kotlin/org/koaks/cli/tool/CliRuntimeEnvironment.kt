@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package org.koaks.cli.tool

import kotlin.native.Platform

internal val currentOperatingSystemName: String
    get() = when (Platform.osFamily.name) {
        "MACOSX" -> "macOS"
        "IOS" -> "iOS"
        "WINDOWS" -> "Windows"
        "LINUX" -> "Linux"
        "ANDROID" -> "Android"
        "TVOS" -> "tvOS"
        "WATCHOS" -> "watchOS"
        else -> Platform.osFamily.name.lowercase()
    }
