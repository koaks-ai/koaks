package org.koaks.framework.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.toolcall.toolinterface.Tool

object ToolManager {

    private val logger = KotlinLogging.logger {}

    // tool implemented using the annotation and interface
    private val container: HashMap<String, ToolDefinition> = HashMap()

    // tool implemented using the tool interface
    private val interfaceContainer: HashMap<String, Tool<*>> = HashMap()

    fun registerTool(tool: ToolDefinition) {
        logger.debug { tool.toJson() }
        container[tool.toolName] = tool
    }

    fun registerInterfaceTools(vararg tool: Tool<*>) {
        tool.forEach {
            logger.debug { "add tool ${it.name}" }
            container[it.name] = it.toDefinition()
        }
    }

    fun getTool(toolname: String): ToolDefinition? = container[toolname]

    fun getTools(vararg group: String): MutableList<ToolDefinition> {
        return container.values.filter { group.contains(it.group) } as MutableList<ToolDefinition>
    }

    fun showContainerStatus() {
        logger.info {
            "\n====== ToolContainer Status ======\n" +
                    "container size: ${container.size}\n" +
                    "container keys: ${container.keys}"
        }
    }

}

/**
 * only implemented using the annotation need
 */
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