package org.koaks.framework

import io.github.cdimascio.dotenv.dotenv

object EnvTools {

    val dotenv = dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    fun loadValue(key: String): String = dotenv[key]

}
