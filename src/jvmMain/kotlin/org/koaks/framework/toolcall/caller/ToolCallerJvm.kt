package org.koaks.framework.toolcall.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.toolcall.ToolContainer
import org.koaks.framework.toolcall.ToolInstanceContainer

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object ToolCaller : IOutCaller {

    private val logger = KotlinLogging.logger {}

    actual override fun call(toolname: String, args: Array<Any>?): String {
        return ToolContainer.getTool(toolname)?.let {
            try {
                val actualArgs = args ?: emptyArray()
                it.realFunction.call(ToolInstanceContainer.getToolInstance(toolname), *actualArgs)?.toString()
            } catch (e: Exception) {
                throw RuntimeException("Error when calling tool $toolname.", e)
            }
        } ?: run {
            logger.error { "Tool $toolname not found." }
            "null"
        }
    }

    actual override fun call(json: String): String {
        TODO("Not yet implemented")
    }

}