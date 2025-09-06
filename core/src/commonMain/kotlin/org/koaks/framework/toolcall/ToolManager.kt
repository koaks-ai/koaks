package org.koaks.framework.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.toolcall.toolinterface.Tool

object ToolManager {

    private val logger = KotlinLogging.logger {}

    private val globalGroupContainer = HashMap<String, MutableList<ToolDefinition>>()

    // check whether there is a duplicate tool name
    private val nameSet = HashSet<String>()

    // tool implemented using the tool interface
    private val interfaceContainer: HashMap<String, Tool<*>> = HashMap()

    fun registerTool(tool: ToolDefinition) {
        logger.debug { tool.toJson() }
        if (nameSet.contains(tool.toolName)) {
            logger.warn { "tool ${tool.toolName} already exists" }
        }
        globalGroupContainer[tool.group]?.add(tool) ?: run {
            globalGroupContainer[tool.group] = mutableListOf(tool)
        }
        nameSet.add(tool.toolName)
    }

    fun getTool(toolName: String): ToolDefinition? =
        globalGroupContainer.values.flatten().firstOrNull { it.toolName == toolName }

    fun getGroupToolList(group: String): MutableList<ToolDefinition>? = globalGroupContainer[group]

    fun showContainerStatus() {
        logger.info {
            "\n====== GlobalToolContainer Status ======\n" +
                    "group size: ${globalGroupContainer.size}\n" +
                    "group keys: ${globalGroupContainer.keys}\n" +
                    "group entries: ${globalGroupContainer.entries}\n"
            "\n========================================\n"
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