package org.koaks.framework.provider

/** How a provider authenticates the request. Default is [Bearer]. */
sealed interface AuthScheme {
    /** Auth headers for [apiKey] (empty when no key is set). */
    fun headers(apiKey: String?): List<Pair<String, String>>

    /** `Authorization: Bearer <key>` — OpenAI / Qwen. The default. */
    data object Bearer : AuthScheme {
        override fun headers(apiKey: String?) =
            if (apiKey.isNullOrBlank()) emptyList()
            else listOf("Authorization" to "Bearer $apiKey")
    }

    /** A header carrying the key verbatim, e.g. `x-api-key` (Anthropic). */
    data class Header(val name: String) : AuthScheme {
        override fun headers(apiKey: String?) =
            if (apiKey.isNullOrBlank()) emptyList() else listOf(name to apiKey)
    }
}
