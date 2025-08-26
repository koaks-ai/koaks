package org.koaks.provider.qwen

import io.github.cdimascio.dotenv.dotenv

object EnvTools {

    val dotenv = dotenv {
        directory = "../"
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    fun loadValue(key: String): String {
        return dotenv[key]
    }

}
