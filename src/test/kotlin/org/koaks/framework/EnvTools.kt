package org.koaks.framework

import io.github.cdimascio.dotenv.dotenv

object EnvTools {

    val dotenv = dotenv {
        directory = "src/test/kotlin/org/koaks/framework"
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    fun loadValue(key: String): String = dotenv[key]
}