package org.koaks.runtime

internal actual fun installDefaultRuntimeShutdownHook(block: () -> Unit) {
    Runtime.getRuntime().addShutdownHook(Thread(block, "koaks-default-runtime-shutdown"))
}
