package org.koaks.framework.toolcall.caller

interface IOutCaller {

    fun call(toolname: String, args: Array<Any>?): String

    fun call(json: String): String

}