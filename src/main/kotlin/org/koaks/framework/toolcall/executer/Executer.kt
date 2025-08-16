package org.koaks.framework.toolcall.executer

interface Executer {

    fun exec(cmd: String): String?

    fun exec(cmd: String, outPath: String): Boolean

}