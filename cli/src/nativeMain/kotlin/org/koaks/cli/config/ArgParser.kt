package org.koaks.cli.config

internal object ArgParser {
    fun parse(args: Array<String>): CliOptions {
        var options = CliOptions()
        var index = 0

        while (index < args.size) {
            val arg = args[index]
            val option = arg.substringBefore("=")

            options = when (option) {
                "-h", "--help" -> options.copy(showHelp = true)
                else -> throw CliException("Unknown option: $option.")
            }
            index += 1
        }

        return options
    }
}
