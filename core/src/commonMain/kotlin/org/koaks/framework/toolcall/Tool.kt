package org.koaks.framework.toolcall

interface Tool {
    val name: String
    val description: String
    suspend fun call(input: String): String
}