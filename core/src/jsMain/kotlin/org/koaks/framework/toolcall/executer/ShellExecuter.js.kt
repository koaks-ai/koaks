package org.koaks.framework.toolcall.executer

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object ShellCodeExecuter : CodeExecuter {
    actual override fun exec(cmd: String): String? {
        console.warn("no need to implementation now, it never used")
        return null
    }

    actual override fun exec(cmd: String, outPath: String): Boolean {
        console.warn("no need to implementation now, it never used")
        return false
    }
}