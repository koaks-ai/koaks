package org.koaks.cli.config

import kotlinx.cinterop.convert
import platform.posix.mkdir

internal actual object ConfigFileSystem {
    actual fun createDirectory(path: String): Boolean =
        mkdir(path, 0x1FF.convert()) == 0
}
