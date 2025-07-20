package org.endowx.framework.toolcall.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endowx.framework.toolcall.ToolInstanceContainer
import org.endowx.framework.toolcall.ToolContainer

class ToolCaller : IOutCaller {

    private val logger = KotlinLogging.logger {}

    override fun call(toolname: String, args: Array<Any>?): String {
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

    override fun call(json: String): String {
        TODO("Not yet implemented")
    }

}