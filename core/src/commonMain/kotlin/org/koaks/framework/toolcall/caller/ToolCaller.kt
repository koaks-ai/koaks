package org.koaks.framework.toolcall.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import org.koaks.framework.toolcall.ToolManager
import org.koaks.framework.toolcall.ToolType
import org.koaks.framework.utils.JsonUtil
import kotlin.collections.component1
import kotlin.collections.component2


object ToolCaller : IOutCaller {

    val logger = KotlinLogging.logger {}

    override suspend fun call(toolname: String, json: String): String {
        return ToolManager.getTool(toolname)?.let {
            if (it.toolType == ToolType.ANNOTATION) {
                val args = parseToolArguments(toolname, json)
                `AnnoTypeCaller.jvm`.call(it, args.toTypedArray())
            } else {
                // only two types: ANNOTATION and INTERFACE
                // for tools of type INTERFACE, toolImplementation can never be null
                it.toolImplementation!!.executeJson(json) ?: "execute success"
            }
        } ?: run {
            logger.error { "Tool $toolname not found." }
            "Tool $toolname not found."
        }
    }

    // todo: need refactoring
    private fun parseToolArguments(toolName: String, rawJson: String?): List<Any> {
        if (rawJson.isNullOrBlank()) return emptyList()

        return try {
            val jsonObject = JsonUtil.fromJson<JsonObject>(rawJson)
            val tool = ToolManager.getTool(toolName)
            tool?.function?.parameters?.properties?.mapNotNull { (key, _) ->
                jsonObject[key]?.toString()
            } ?: emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse arguments for $toolName" }
            emptyList()
        }
    }

}
