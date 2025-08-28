package org.koaks.framework.toolcall.caller

import org.koaks.framework.toolcall.ToolDefinition

interface IOutCaller {

    suspend fun call(toolname: String, json: String, toolContainer: Map<String, ToolDefinition>): String

}