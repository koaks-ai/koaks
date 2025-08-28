package org.koaks.framework.toolcall.caller

import org.koaks.framework.toolcall.ToolDefinition

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object AnnoTypeCaller {
    actual suspend fun call(
        tool: ToolDefinition,
        args: Array<Any>?
    ): String {
        console.error("no need to implementation, it's only for jvm")
        console.error(
            "If you are seeing this log message, " +
                    "it means the program is running in an abnormal manner."
        )
        return "no need to implementation, it's only for jvm"
    }
}