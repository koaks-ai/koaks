package org.koaks.runtime

internal expect fun installDefaultRuntimeShutdownHook(block: () -> Unit)
