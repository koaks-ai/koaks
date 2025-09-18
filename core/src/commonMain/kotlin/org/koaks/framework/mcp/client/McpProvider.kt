package org.koaks.framework.mcp.client

interface McpProvider {

    fun serverName(): String

    fun serverUrl(): String

    fun serverKey(): String

}