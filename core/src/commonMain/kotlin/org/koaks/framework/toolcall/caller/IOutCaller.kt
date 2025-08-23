package org.koaks.framework.toolcall.caller

interface IOutCaller {

    suspend fun call(toolname: String, json: String): String

}