package org.koaks.framework.toolcall

import kotlinx.serialization.KSerializer


class ToolBuilder<T>(val serializer: KSerializer<T>) {
    var name: String = ""
    var description: String = ""
    var group: String = "default"
    lateinit var execute: suspend (T) -> String

    fun build(): Tool<T> = object : Tool<T> {
        override val name = this@ToolBuilder.name
        override val description = this@ToolBuilder.description
        override val group = this@ToolBuilder.group
        override val serializer = this@ToolBuilder.serializer
        override suspend fun execute(input: T): String = this@ToolBuilder.execute(input)
    }
}


inline fun <reified T> createTool(block: ToolBuilder<T>.() -> Unit): Tool<T> {
    val builder = ToolBuilder(kotlinx.serialization.serializer<T>())
    return builder.apply(block).build()
}

inline fun <reified T> createTool(
    name: String,
    description: String,
    group: String = "default",
    noinline block: suspend (T) -> String
): Tool<T> {
    return object : Tool<T> {
        override val name = name
        override val description = description
        override val group = group
        override val serializer = kotlinx.serialization.serializer<T>()
        override suspend fun execute(input: T): String = block(input)
    }
}
