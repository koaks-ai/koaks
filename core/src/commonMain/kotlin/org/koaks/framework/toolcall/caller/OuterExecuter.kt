package org.koaks.framework.toolcall.caller

interface OuterExecuter {

    suspend fun call(toolname: String, json: String): String

}