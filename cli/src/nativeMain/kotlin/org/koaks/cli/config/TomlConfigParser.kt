package org.koaks.cli.config

internal object TomlConfigParser {
    fun parse(text: String, sourceName: String = "config.toml"): FileConfig {
        val root = RootBuilder()
        val providers = mutableMapOf<Provider, ProviderBuilder>()
        val providerOrder = mutableListOf<Provider>()
        var section: Section = Section.Root
        val lines = text.lines()
        var index = 0

        while (index < lines.size) {
            var line = stripComment(lines[index]).trim()
            val lineNumber = index + 1

            if (line.isBlank()) {
                index += 1
                continue
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                section = parseSection(line, sourceName, lineNumber)
                if (section is Section.ProviderTable && section.provider !in providers) {
                    providers[section.provider] = ProviderBuilder()
                    providerOrder += section.provider
                }
                index += 1
                continue
            }

            val equalsIndex = line.indexOf('=')
            if (equalsIndex <= 0) {
                configError(sourceName, lineNumber, "Expected key = value.")
            }

            val key = line.take(equalsIndex).trim()
            var value = line.drop(equalsIndex + 1).trim()
            while (value.startsWith("[") && !arrayClosed(value)) {
                index += 1
                if (index >= lines.size) {
                    configError(sourceName, lineNumber, "Unclosed array for '$key'.")
                }
                value += "\n${stripComment(lines[index]).trim()}"
            }

            when (val activeSection = section) {
                Section.Root -> root.apply(key, value, sourceName, lineNumber)
                is Section.ProviderTable -> providers.getValue(activeSection.provider)
                    .apply(key, value, sourceName, lineNumber)
            }

            index += 1
        }

        return FileConfig(
            defaultProvider = root.defaultProvider,
            defaultModel = root.defaultModel,
            instructions = root.instructions,
            threadId = root.threadId,
            historyMessages = root.historyMessages,
            temperature = root.temperature,
            showReasoning = root.showReasoning,
            skillPaths = root.skillPaths,
            skills = root.skills,
            providers = providers.mapValues { it.value.build() },
            providerOrder = providerOrder,
        )
    }

    private fun parseSection(line: String, sourceName: String, lineNumber: Int): Section {
        val name = line.removePrefix("[").removeSuffix("]").trim()
        if (name.isBlank()) configError(sourceName, lineNumber, "Empty section name.")

        val parts = name.split(".").map { it.trim() }
        if (parts.size == 2 && parts[0] == "providers") {
            return Section.ProviderTable(parseProvider(parts[1], sourceName, lineNumber))
        }

        configError(sourceName, lineNumber, "Unsupported section [$name]. Use [providers.<provider>].")
    }
}

private sealed interface Section {
    data object Root : Section
    data class ProviderTable(val provider: Provider) : Section
}

private class RootBuilder {
    var defaultProvider: Provider? = null
    var defaultModel: String? = null
    var instructions: String? = null
    var threadId: String? = null
    var historyMessages: Int? = null
    var temperature: Double? = null
    var showReasoning: Boolean? = null
    var skillPaths: List<String> = emptyList()
    var skills: List<String> = emptyList()

    fun apply(key: String, value: String, sourceName: String, lineNumber: Int) {
        when (key) {
            "provider", "default_provider" -> defaultProvider = parseProviderString(value, sourceName, lineNumber)
            "model", "model_name", "default_model" -> defaultModel = parseNonBlankString(key, value, sourceName, lineNumber)
            "instructions" -> instructions = parseNonBlankString(key, value, sourceName, lineNumber)
            "thread", "thread_id" -> threadId = parseNonBlankString(key, value, sourceName, lineNumber)
            "history", "history_messages" -> historyMessages = parsePositiveIntValue(key, value, sourceName, lineNumber)
            "temperature" -> temperature = parseDoubleValue(key, value, sourceName, lineNumber)
            "show_reasoning", "reasoning" -> showReasoning = parseBooleanValue(key, value, sourceName, lineNumber)
            "skill_paths" -> skillPaths = parseStringList(key, value, sourceName, lineNumber)
            "skills" -> skills = parseStringList(key, value, sourceName, lineNumber)
            else -> configError(sourceName, lineNumber, "Unknown top-level key '$key'.")
        }
    }
}

private class ProviderBuilder {
    var baseUrl: String? = null
    var apiKey: String? = null
    var defaultModel: String? = null
    var modelList: List<String> = emptyList()

    fun apply(key: String, value: String, sourceName: String, lineNumber: Int) {
        when (key) {
            "base_url", "baseUrl" -> baseUrl = parseNonBlankString(key, value, sourceName, lineNumber)
            "api_key", "apiKey" -> apiKey = parseNonBlankString(key, value, sourceName, lineNumber)
            "model", "model_name", "default_model" -> defaultModel = parseNonBlankString(key, value, sourceName, lineNumber)
            "model_list", "models" -> modelList = parseStringList(key, value, sourceName, lineNumber)
            else -> configError(sourceName, lineNumber, "Unknown provider key '$key'.")
        }
    }

    fun build(): FileProviderConfig =
        FileProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = defaultModel,
            modelList = modelList,
        )
}

private fun stripComment(line: String): String {
    var quote: Char? = null
    var escaped = false
    line.forEachIndexed { index, char ->
        when {
            quote == '"' && escaped -> escaped = false
            quote == '"' && char == '\\' -> escaped = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '#' -> return line.take(index)
        }
    }
    return line
}

private fun arrayClosed(value: String): Boolean {
    var quote: Char? = null
    var escaped = false
    var depth = 0

    for (char in value) {
        when {
            quote == '"' && escaped -> escaped = false
            quote == '"' && char == '\\' -> escaped = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '[' -> depth += 1
            quote == null && char == ']' -> depth -= 1
        }
    }

    return depth == 0 && quote == null
}

private fun parseProviderString(value: String, sourceName: String, lineNumber: Int): Provider =
    parseProvider(parseStringValue(value, sourceName, lineNumber), sourceName, lineNumber)

private fun parseProvider(value: String, sourceName: String, lineNumber: Int): Provider {
    val providerId = value.trim()
    return Provider.entries.firstOrNull { it.id == providerId.lowercase() }
        ?: configError(sourceName, lineNumber, "Unknown provider '$value'. Expected ${Provider.idsForMessage()}.")
}

private fun parseNonBlankString(key: String, value: String, sourceName: String, lineNumber: Int): String {
    val parsed = parseStringValue(value, sourceName, lineNumber).trim()
    if (parsed.isBlank()) configError(sourceName, lineNumber, "$key cannot be blank.")
    return parsed
}

private fun parsePositiveIntValue(key: String, value: String, sourceName: String, lineNumber: Int): Int {
    val parsed = parseStringValue(value, sourceName, lineNumber).toIntOrNull()
        ?: configError(sourceName, lineNumber, "$key must be a positive integer.")
    if (parsed <= 0) configError(sourceName, lineNumber, "$key must be a positive integer.")
    return parsed
}

private fun parseDoubleValue(key: String, value: String, sourceName: String, lineNumber: Int): Double =
    parseStringValue(value, sourceName, lineNumber).toDoubleOrNull()
        ?: configError(sourceName, lineNumber, "$key must be a number.")

private fun parseBooleanValue(key: String, value: String, sourceName: String, lineNumber: Int): Boolean =
    when (parseStringValue(value, sourceName, lineNumber).lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> configError(sourceName, lineNumber, "$key must be true or false.")
    }

private fun parseStringValue(value: String, sourceName: String, lineNumber: Int): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) configError(sourceName, lineNumber, "Value cannot be blank.")

    return when (trimmed.first()) {
        '"' -> parseQuotedString(trimmed, '"', sourceName, lineNumber)
        '\'' -> parseQuotedString(trimmed, '\'', sourceName, lineNumber)
        else -> trimmed
    }
}

private fun parseQuotedString(value: String, quote: Char, sourceName: String, lineNumber: Int): String {
    if (value.length < 2 || value.last() != quote) {
        configError(sourceName, lineNumber, "Unclosed string.")
    }

    val inner = value.substring(1, value.lastIndex)
    if (quote == '\'') return inner

    val parsed = StringBuilder()
    var escaped = false
    for (char in inner) {
        if (escaped) {
            parsed.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    else -> char
                }
            )
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else {
            parsed.append(char)
        }
    }
    if (escaped) parsed.append('\\')
    return parsed.toString()
}

private fun parseStringList(key: String, value: String, sourceName: String, lineNumber: Int): List<String> {
    val trimmed = value.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
        configError(sourceName, lineNumber, "Expected an array of strings.")
    }

    val body = trimmed.substring(1, trimmed.lastIndex)
    val items = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var escaped = false

    fun flushToken() {
        val raw = token.toString().trim()
        token.clear()
        if (raw.isNotEmpty()) {
            items += parseNonBlankString(key, raw, sourceName, lineNumber)
        }
    }

    for (char in body) {
        if (quote == null && char == ',') {
            flushToken()
            continue
        }

        token.append(char)
        when {
            quote == '"' && escaped -> escaped = false
            quote == '"' && char == '\\' -> escaped = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
        }
    }

    if (quote != null) configError(sourceName, lineNumber, "Unclosed string in array.")
    flushToken()
    return items
}

private fun configError(sourceName: String, lineNumber: Int, message: String): Nothing {
    throw CliException("$sourceName:$lineNumber: $message")
}
