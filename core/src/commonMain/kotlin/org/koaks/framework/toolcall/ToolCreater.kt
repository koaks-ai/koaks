package org.koaks.framework.toolcall


class ToolBuilder<T> {
    var name: String = ""
    var description: String = ""
    lateinit var execute: suspend (T) -> String

    fun build(): Tool<T> = object : Tool<T> {
        override val name = this@ToolBuilder.name
        override val description = this@ToolBuilder.description
        override suspend fun execute(input: T): String = this@ToolBuilder.execute(input)
    }
}

inline fun <reified T> createTool(block: ToolBuilder<T>.() -> Unit): Tool<T> {
    return ToolBuilder<T>().apply(block).build()
}

fun <T> createTool(
    name: String,
    description: String,
    block: suspend (T) -> String
): Tool<T> {
    return object : Tool<T> {
        override val name = name
        override val description = description
        override suspend fun execute(input: T): String = block(input)
    }
}
