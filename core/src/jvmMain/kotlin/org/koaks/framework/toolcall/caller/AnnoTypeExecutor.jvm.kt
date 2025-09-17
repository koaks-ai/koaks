package org.koaks.framework.toolcall.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.toolcall.ToolDefinition
import org.koaks.framework.toolcall.ToolInstanceContainer

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object AnnoTypeExecutor {

    private val logger = KotlinLogging.logger {}

    actual suspend fun call(tool: ToolDefinition, args: Array<Any>?): String {
        val toolName = tool.toolName
        return try {
            val actualArgs = args ?: emptyArray()
            // For tools registered using annotations, there’s no need to manually add them to ChatModel.toolContainer.
            // When retrieving them via group name, they are obtained from the GlobalToolManager.
            // During the automatic scanning and registration to the GlobalToolManager, they are also placed into the ToolInstanceContainer at the same time,
            // so the corresponding instance can definitely be retrieved here.
            tool.realFunction!!.call(ToolInstanceContainer.getToolInstance(toolName), *actualArgs)?.toString()
        } catch (e: Exception) {
            logger.error(e) { "Error when calling tool $toolName. Error message: ${e.message}" }
            "Error when calling tool $toolName."
        }.toString()

    }

}