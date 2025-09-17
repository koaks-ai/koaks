package org.koaks.framework.mcp.client

interface McpProvider {

    fun serverUrl(): String

    fun serverKey(): String

}