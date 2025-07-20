package org.endowx.framework.toolcall

import org.endowx.framework.annotation.Tool
import org.endowx.framework.context.EndowContext
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import kotlin.collections.forEach
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.kotlinFunction

object ToolCallInitializer {

    @Volatile
    private var isInitialized = false

    private val classInstanceCache = mutableMapOf<KClass<*>, Any>()

    @Synchronized
    fun init() {
        if (isInitialized) return

        val tools = scanTools(EndowContext.getPackageName())
        registerTools(tools)
        instanceToolClass(tools)

        isInitialized = true
    }

    private fun scanTools(packageName: Array<String>): List<ToolDefinition> {
        return Reflections(packageName, Scanners.MethodsAnnotated)
            .getMethodsAnnotatedWith(Tool::class.java)
            .mapNotNull { it.kotlinFunction }
            .filter { it.annotations.any { ann -> ann is Tool } }
            .mapNotNull { function ->
                val toolAnnotation = function.findAnnotation<Tool>() ?: return@mapNotNull null

                val paramAnnotations = toolAnnotation.params
                val paramTypes = function.parameters
                    .filter { it.kind == KParameter.Kind.VALUE }
                    .associateBy { it.name }

                val properties = paramAnnotations.associate { param ->
                    val kParam = paramTypes[param.param]

                    val type = when (kParam?.type?.classifier) {
                        String::class -> "string"
                        Int::class, Long::class, Short::class -> "integer"
                        Float::class, Double::class -> "number"
                        Boolean::class -> "boolean"
                        List::class, Set::class -> "array"
                        Map::class -> "object"
                        else -> throw IllegalArgumentException("Unsupported param: ${param.param}")
                    }

                    param.param to ToolDefinition.Property(
                        type = type,
                        description = param.description
                    )
                }

                val required = paramAnnotations
                    .filter { it.required }
                    .map { it.param }
                    .toTypedArray()

                val toolParams = ToolDefinition.ToolParameters(
                    properties = properties,
                    required = required
                )

                ToolDefinition(
                    function = ToolDefinition.Function(
                        name = function.name,
                        description = toolAnnotation.description,
                        parameters = toolParams
                    )
                ).apply {
                    realFunction = function
                    group = toolAnnotation.group
                }
            }
    }

    private fun registerTools(tools: List<ToolDefinition>) {
        tools.forEach {
            ToolContainer.addTool(it)
        }
    }

    private fun instanceToolClass(tools: List<ToolDefinition>) {
        tools.forEach { tool ->
            val function = tool.realFunction
            val klass = function.javaMethod?.declaringClass?.kotlin ?: return@forEach

            val instance = classInstanceCache.getOrPut(klass) {
                klass.objectInstance ?: klass.createInstance()
            }

            ToolInstanceContainer.addToolInstance(tool.toolname, instance)
        }
    }

}