package org.endow.framework.toolcall.caller

interface IOutCaller {

    fun call(toolname: String, args: Array<Any>): String

}