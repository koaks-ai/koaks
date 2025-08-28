package org.koaks.framework.toolcall.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.toolcall.ToolDefinition
import org.koaks.framework.toolcall.ToolInstanceContainer

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object `AnnoTypeCaller.jvm` {

    private val logger = KotlinLogging.logger {}

    actual suspend fun call(tool: ToolDefinition, args: Array<Any>?): String {
        val toolName = tool.toolName
        return tool.let {
            try {
                val actualArgs = args ?: emptyArray()
                it.realFunction!!.call(ToolInstanceContainer.getToolInstance(toolName), *actualArgs)?.toString()
            } catch (e: Exception) {
                throw RuntimeException("Error when calling tool $toolName.", e)
            }
        } ?: run {
            logger.error { "Tool $toolName not found." }
            "null"
        }
    }

}