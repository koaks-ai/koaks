package org.koaks.framework.toolcall.executor


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ShellCodeExecutor : CodeExecutor {

    override fun exec(cmd: String): String?

    override fun exec(cmd: String, outPath: String): Boolean

}