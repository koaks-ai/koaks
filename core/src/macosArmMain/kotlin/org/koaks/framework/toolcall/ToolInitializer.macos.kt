package org.koaks.framework.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object ToolInitializer {

    private val logger = KotlinLogging.logger {}

    actual fun init() {
        // no need to implementation, it's only for jvm
        logger.error { "no need to implementation, it's only for jvm" }
        logger.error {
            "If you are seeing this log message, " +
                    "it means the program is running in an abnormal manner."
        }
    }

    actual fun scanTools(packageName: List<String>): List<ToolDefinition> {
        // no need to implementation, it's only for jvm
        return emptyList()
    }

    actual fun registerTools(tools: List<ToolDefinition>) {
        // no need to implementation, it's only for jvm
    }

    actual fun instanceToolClass(tools: List<ToolDefinition>) {
        // no need to implementation, it's only for jvm
    }
}