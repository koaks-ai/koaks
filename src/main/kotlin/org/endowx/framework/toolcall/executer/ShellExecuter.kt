package org.endow.framework.toolcall.executer

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.*
import java.util.*

object ShellExecuter : Executer {

    private val logger = KotlinLogging.logger {}

    override fun exec(cmd: String): String {
        val output = StringBuilder()
        try {
            val command: String? = if (isFile(cmd)) readFile(cmd) else cmd
            val process = Runtime.getRuntime().exec(this.commandPrefix + command)
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    output.append(line).append(System.lineSeparator())
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            logger.error { "Failed to execute command:${e.message}" }
        }
        return output.toString().trim { it <= ' ' }
    }

    override fun exec(cmd: String, outPath: String): Boolean {
        try {
            FileWriter(outPath).use { writer ->
                val command: String? = if (isFile(cmd)) readFile(cmd) else cmd
                val process = Runtime.getRuntime().exec(this.commandPrefix + command)
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        writer.write(line)
                        writer.write(System.lineSeparator())
                    }
                }
                process.waitFor()
                return true
            }
        } catch (e: Exception) {
            logger.error { "[output] Failed to execute command:${e.message}" }
            return false
        }
    }

    private fun isFile(cmdOrPath: String): Boolean {
        val file = File(cmdOrPath)
        return file.exists() && file.isFile()
    }

    private fun readFile(filePath: String): String {
        val content = StringBuilder()
        BufferedReader(FileReader(filePath)).use { reader ->
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                content.append(line).append(" ")
            }
        }
        return content.toString().trim { it <= ' ' }
    }

    private val commandPrefix: String
        get() {
            val os = System.getProperty("os.name").lowercase(Locale.getDefault())
            return if (os.contains("win")) {
                "cmd /c "
            } else {
                "bash -c "
            }
        }
}