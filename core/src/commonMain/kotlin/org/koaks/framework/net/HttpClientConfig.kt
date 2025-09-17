package org.koaks.framework.net

data class HttpClientConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val connectTimeout: Long = 5,
    val writeTimeout: Long = 60,
    // todo: need to distinguish between `streaming` and `non-streaming`
    val readTimeout: Long = 600,
    // todo: need to distinguish between `chat` and `agent`
    val callTimeout: Long = 600,
    val streamEndMarker: String = "[DONE]",
    val customHeaders: Map<String, String> = emptyMap()
)