package org.koaks.framework.net

data class HttpClientConfig(
    val baseUrl: String,
    val apiKey: String,
    val timeoutSeconds: Long = 60,
    val maxInMemorySize: Int = 256 * 1024,
    val streamEndMarker: String = "[DONE]"
)