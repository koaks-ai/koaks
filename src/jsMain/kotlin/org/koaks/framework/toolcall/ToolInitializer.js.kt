package org.koaks.framework.toolcall

import org.koaks.framework.context.KoaksContext
import kotlin.reflect.KClass


@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual object ToolInitializer {

    private var isInitialized = false

    private val classInstanceCache = mutableMapOf<KClass<*>, Any>()

    actual fun init() {
        if (isInitialized) return

        val tools = scanTools(KoaksContext.getPackageName())
        registerTools(tools)
        instanceToolClass(tools)

        isInitialized = true
    }

    actual fun scanTools(packageName: Array<String>): List<ToolDefinition> {
        TODO("Not yet implemented")
    }

    actual fun registerTools(tools: List<ToolDefinition>) {
        TODO("Not yet implemented")
    }

    actual fun instanceToolClass(tools: List<ToolDefinition>) {
        TODO("Not yet implemented")
    }

}