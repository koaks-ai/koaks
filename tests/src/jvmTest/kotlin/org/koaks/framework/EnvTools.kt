package org.koaks.framework

import io.github.cdimascio.dotenv.dotenv
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object EnvTools {

    private val logger = KotlinLogging.logger {}

    private val dotenv by lazy {
        dotenv {
            directory = "../"
            filename = ".env"
            ignoreIfMalformed = true
            ignoreIfMissing = true
        }
    }

    actual fun loadValue(key: String): String {
        return dotenv[key] ?: run {
            logger.error { "No value found for key $key" }
            "null"
        }
    }

}
