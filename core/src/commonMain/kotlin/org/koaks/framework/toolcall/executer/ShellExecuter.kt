package org.koaks.framework.toolcall.executer


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ShellExecuter : Executer {

    override fun exec(cmd: String): String?

    override fun exec(cmd: String, outPath: String): Boolean

}