package org.koaks.framework.toolcall.caller


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ToolCaller : IOutCaller {

    override fun call(toolname: String, args: Array<Any>?): String

    override fun call(json: String): String

}
