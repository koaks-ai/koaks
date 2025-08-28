package org.koaks.framework.toolcall

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object ToolInitializer {
    actual fun init() {
        // no need to implementation, it's only for jvm
        console.error("no need to implementation, it's only for jvm")
        console.error(
            "If you are seeing this log message, " +
                    "it means the program is running in an abnormal manner."
        )
    }

    actual fun scanTools(packageName: List<String>): List<ToolDefinition> {
        // no need to implementation, it's only for jvm
        console.error("no need to implementation, it's only for jvm")
        console.error(
            "If you are seeing this log message, " +
                    "it means the program is running in an abnormal manner."
        )
        return emptyList()
    }

    actual fun registerTools(tools: List<ToolDefinition>) {
        // no need to implementation, it's only for jvm
        console.error("no need to implementation, it's only for jvm")
        console.error(
            "If you are seeing this log message, " +
                    "it means the program is running in an abnormal manner."
        )
    }

    actual fun instanceToolClass(tools: List<ToolDefinition>) {
        // no need to implementation, it's only for jvm
        console.error("no need to implementation, it's only for jvm")
        console.error(
            "If you are seeing this log message, " +
                    "it means the program is running in an abnormal manner."
        )
    }
}