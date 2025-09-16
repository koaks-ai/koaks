package org.koaks.framework.mcp

import kotlinx.serialization.Serializable

@Serializable
data class InitRequest(
    var jsonrpc: String = "2.0",
    val id: Int,
    var method: String = "initialize",
    val params: Params
) {
    @Serializable
    data class Params(
        val protocolVersion: String,
        val capabilities: Capabilities,
        val clientInfo: ClientInfo
    )

    @Serializable
    data class Capabilities(
        val roots: Roots,
        val sampling: Sampling
    )

    @Serializable
    data class Roots(
        val listChanged: Boolean
    )

    @Serializable
    class Sampling

    @Serializable
    data class ClientInfo(
        val name: String,
        val version: String
    )

}
