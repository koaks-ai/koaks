package org.koaks.framework.toolcall.executer

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object ShellExecuter : Executer {

    actual override fun exec(cmd: String): String? {
        TODO("Not yet implemented")
    }

    actual override fun exec(cmd: String, outPath: String): Boolean {
        TODO("Not yet implemented")
    }

}