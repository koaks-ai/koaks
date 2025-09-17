package org.koaks.framework.toolcall.caller

import org.koaks.framework.toolcall.ToolDefinition

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AnnoTypeExecutor {

    suspend fun call(tool: ToolDefinition, args: Array<Any>?): String

}