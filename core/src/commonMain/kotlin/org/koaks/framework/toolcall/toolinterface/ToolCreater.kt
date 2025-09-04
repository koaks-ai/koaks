package org.koaks.framework.toolcall.toolinterface

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer


class ToolBuilder<T>(val serializer: KSerializer<T>) {
    var name: String = ""
    var description: String = ""
    var group: String = "default"
    var returnDirectly: Boolean = false
    lateinit var execute: suspend (T) -> String

    fun build(): Tool<T> = object : Tool<T> {
        override val name = this@ToolBuilder.name
        override val description = this@ToolBuilder.description
        override val group = this@ToolBuilder.group
        override val serializer = this@ToolBuilder.serializer
        override val returnDirectly = true
        override suspend fun execute(input: T): String = this@ToolBuilder.execute(input)
    }
}


inline fun <reified T> createTool(block: ToolBuilder<T>.() -> Unit): Tool<T> {
    val builder = ToolBuilder(serializer<T>())
    return builder.apply(block).build()
}

inline fun <reified T> createTool(
    name: String,
    description: String,
    group: String = "default",
    returnDirectly: Boolean = false,
    noinline block: suspend (T) -> String
): Tool<T> {
    return object : Tool<T> {
        override val name = name
        override val description = description
        override val group = group
        override val serializer = serializer<T>()
        override val returnDirectly = returnDirectly
        override suspend fun execute(input: T): String = block(input)
    }
}
