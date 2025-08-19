package org.koaks.framework.net

class HttpClientException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class JsonParseException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)