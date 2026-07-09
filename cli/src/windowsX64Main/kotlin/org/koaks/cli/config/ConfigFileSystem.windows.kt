package org.koaks.cli.config

import platform.posix.mkdir

internal actual object ConfigFileSystem {
    actual fun createDirectory(path: String): Boolean =
        mkdir(path) == 0
}
