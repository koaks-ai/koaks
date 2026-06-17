package examples

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.nio.file.Files
import java.nio.file.Path

object EnvTools {

    private val env = dotenv {
        directory = findEnvDirectory()
        filename = ".env"
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    fun loadValue(key: String): String = env.required(key)

    private fun findEnvDirectory(): String {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve(".env"))) {
                return current.toString()
            }
            current = current.parent
        }
        return "."
    }

    private fun Dotenv.required(key: String): String =
        optional(key) ?: error("Missing $key in project root .env")

    private fun Dotenv.optional(key: String): String? =
        this[key]?.takeIf { it.isNotBlank() && it != "changeme" && it != "null" }

}