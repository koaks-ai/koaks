package org.koaks.framework.toolcall.executer

import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object ShellCodeExecuter : CodeExecuter {

    private val logger = KotlinLogging.logger {}

    actual override fun exec(cmd: String): String? {
        logger.error { "no need to implementation now, it never used" }
        return null
    }

    actual override fun exec(cmd: String, outPath: String): Boolean {
        logger.error { "no need to implementation now, it never used" }
        return false
    }

}