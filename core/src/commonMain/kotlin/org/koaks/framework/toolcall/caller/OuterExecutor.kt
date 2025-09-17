package org.koaks.framework.toolcall.caller

interface OuterExecutor {

    suspend fun call(toolname: String, json: String): String

}