package org.koaks.cli.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArgParserTest {
    @Test
    fun parsesHelpFlag() {
        val options = ArgParser.parse(arrayOf("--help"))

        assertEquals(true, options.showHelp)
    }

    @Test
    fun rejectsProviderOptionBecauseConfigComesFromFile() {
        val error = assertFailsWith<CliException> {
            ArgParser.parse(arrayOf("--provider=openai"))
        }

        assertTrue(error.message!!.contains("Unknown option"))
    }

    @Test
    fun rejectsUnknownOption() {
        val error = assertFailsWith<CliException> {
            ArgParser.parse(arrayOf("--bogus"))
        }

        assertTrue(error.message!!.contains("Unknown option"))
    }
}
