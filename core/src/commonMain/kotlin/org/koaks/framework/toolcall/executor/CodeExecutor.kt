package org.koaks.framework.toolcall.executor

interface CodeExecutor {

    fun exec(cmd: String): String?

    fun exec(cmd: String, outPath: String): Boolean

}