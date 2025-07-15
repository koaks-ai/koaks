package org.endow.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endow.framework.context.Status
import org.endow.framework.toolcall.ToolCallInitializer

object EndowFramework {

    private val logger = KotlinLogging.logger {}

    fun init(packageName: Array<String>) {
        Status.packageName = packageName.get(0)
        ToolCallInitializer.init()
    }

}