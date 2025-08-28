package org.koaks.framework.toolcall

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ToolInitializer {

    // todo: to be optimized
    fun init()

    fun scanTools(packageName: List<String>): List<ToolDefinition>

    fun registerTools(tools: List<ToolDefinition>)

    fun instanceToolClass(tools: List<ToolDefinition>)

}