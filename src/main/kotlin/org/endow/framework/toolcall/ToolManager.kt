package org.endow.framework.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging

object ToolContainer {

    private val logger = KotlinLogging.logger {}

    private val container: HashMap<String, ToolDefinition> = HashMap()

    fun addTool(tool: ToolDefinition) {
        logger.debug { tool.toJson() }
        container[tool.toolname] = tool
    }

    fun getTool(toolname: String): ToolDefinition? = container[toolname]

    fun getTools(group: String): List<ToolDefinition> {
        return container.values.filter { it.group == group }
    }

    fun showContainerStatus() {
        logger.info {
            "\n====== ToolContainer Status ======\n" +
                    "container size: ${container.size}\n" +
                    "container keys: ${container.keys}"
        }
    }

}

object ToolInstanceContainer {

    private val logger = KotlinLogging.logger {}

    private val toolInstanceContainer = mutableMapOf<String, Any>()

    fun addToolInstance(toolname: String, instance: Any) {
        logger.debug { "add tool instance: $toolname" }
        toolInstanceContainer[toolname] = instance
    }

    fun getToolInstance(toolname: String): Any? = toolInstanceContainer[toolname]

    fun showToolInstanceContainerStatus() {
        logger.info {
            "\n====== ToolInstanceContainer Status ======\n" +
                    "container size: ${toolInstanceContainer.size}\n" +
                    "container keys: ${toolInstanceContainer.keys}"
        }
    }

}