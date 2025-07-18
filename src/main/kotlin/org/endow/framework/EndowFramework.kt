package org.endow.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endow.framework.context.EndowContext
import org.endow.framework.toolcall.ToolCallInitializer

object EndowFramework {

    private val logger = KotlinLogging.logger {}

    fun init(packageName: Array<String>) {
        EndowContext.packageName = packageName
        ToolCallInitializer.init()
        logger.info { "EndowFramework initialized" }
    }

}