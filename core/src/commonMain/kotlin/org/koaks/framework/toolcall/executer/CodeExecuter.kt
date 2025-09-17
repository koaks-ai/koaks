package org.koaks.framework.toolcall.executer

interface CodeExecuter {

    fun exec(cmd: String): String?

    fun exec(cmd: String, outPath: String): Boolean

}