@file:OptIn(ExperimentalNativeApi::class)

package org.koaks.cli

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.koaks.cli.app.AgentApp
import org.koaks.cli.app.formatCrashReport
import org.koaks.cli.app.printFatal
import org.koaks.cli.config.ArgParser
import org.koaks.cli.config.CliException
import org.koaks.cli.config.ConfigResolver
import org.koaks.cli.config.PosixEnvironment
import org.koaks.cli.config.usageText
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.StdoutOutput

fun main(args: Array<String>) {
    setUnhandledExceptionHook { error ->
        println(formatCrashReport(error))
        terminateWithUnhandledException(error)
    }

    runBlocking {
        val output = StdoutOutput()
        val options = try {
            ArgParser.parse(args)
        } catch (e: CliException) {
            printError(output, e.message ?: "Invalid arguments.")
            output.writeLine(usageText())
            return@runBlocking
        }

        if (options.showHelp) {
            output.writeLine(usageText())
            return@runBlocking
        }

        val config = try {
            ConfigResolver.resolve(PosixEnvironment)
        } catch (e: CliException) {
            printError(output, e.message ?: "Invalid configuration.")
            output.writeLine(usageText())
            return@runBlocking
        }

        try {
            AgentApp(initialConfig = config, output = output).run()
        } catch (t: Throwable) {
            printFatal(output, t)
            exitProcess(1)
        }
    }
}

private fun printError(output: Output, message: String) {
    output.writeLine("[error] $message")
}
