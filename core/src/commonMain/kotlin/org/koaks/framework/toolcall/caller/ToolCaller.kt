package org.koaks.framework.toolcall.caller


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ToolCaller : IOutCaller {

    override suspend fun call(toolname: String, json: String): String

}
