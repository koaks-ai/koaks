package org.koaks.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.context.KoaksContext
import org.koaks.framework.toolcall.ToolCallInitializer

object Koaks {

    private val logger = KotlinLogging.logger {}

    fun init(vararg packageName: String) {
        packageName.forEach {
            KoaksContext.addPackageName(it)
        }
        ToolCallInitializer.init()
        logger.info { "Koaks initialized" }
    }

}