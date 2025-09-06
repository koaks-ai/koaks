package org.koaks.framework.context

object KoaksContext {

    private var scanPackageName: MutableList<String> = mutableListOf()

    private val clientToolContainer: MutableMap<String, MutableList<String>> = mutableMapOf()

    init {
        this.scanPackageName.add("org.koaks.framework")
    }

    fun getPackageName(): List<String> {
        return this.scanPackageName
    }

    fun addPackageName(packageName: String) {
        this.scanPackageName.add(packageName)
    }

    fun registerTool(clientId: String, toolName: String) {
        clientToolContainer[clientId]?.add(toolName) ?: run {
            clientToolContainer[clientId] = mutableListOf(toolName)
        }
    }

    fun registerToolList(clientId: String, availableTools: MutableList<String>) {
        clientToolContainer[clientId] = availableTools
    }

    fun getAvailableTools(clientId: String): MutableList<String>? {
        return clientToolContainer[clientId]
    }

}