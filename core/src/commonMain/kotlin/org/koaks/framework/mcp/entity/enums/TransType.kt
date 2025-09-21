package org.koaks.framework.mcp.entity.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TransType {

    @SerialName("stdio")
    STDIO,

    @SerialName("sse")
    SSE,

    @SerialName("StreamableHttp")
    STREAMABLE_HTTP

}