package org.koaks.framework.toolcall

interface Tool<T> {
    val name: String
    val description: String
    suspend fun execute(input: T): String
}
