package org.endow.framework.toolcall.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endow.framework.toolcall.ToolInstanceContainer
import org.endow.framework.toolcall.ToolContainer

object ToolCaller : IOutCaller {

    private val logger = KotlinLogging.logger {}

    override fun call(toolname: String, args: Array<Any>): String {
        return ToolContainer.getTool(toolname)?.let {
            try {
                it.realFunction.call(ToolInstanceContainer.getToolInstance(toolname), *args)?.toString()
            } catch (e: Exception) {
                throw RuntimeException("Error when calling tool $toolname.", e)
            }
        } ?: run {
            logger.error { "Tool $toolname not found." }
            "null"
        }
    }

}