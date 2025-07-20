package org.endowx.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endowx.framework.context.EndowContext
import org.endowx.framework.toolcall.ToolCallInitializer

object EndowFramework {

    private val logger = KotlinLogging.logger {}

    fun init(packageName: Array<String>) {
        packageName.forEach {
            EndowContext.addPackageName(it)
        }
        ToolCallInitializer.init()
        logger.info { "EndowFramework initialized" }
    }

}