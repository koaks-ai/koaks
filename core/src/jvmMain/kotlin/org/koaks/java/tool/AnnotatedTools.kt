package org.koaks.java.tool

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.concurrent.CompletionStage
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.annotation.Param
import org.koaks.framework.annotation.Tool as ToolAnnotation
import org.koaks.framework.annotation.resolvedDescription
import org.koaks.framework.tool.Tool
import org.koaks.java.internal.awaitStage
import org.koaks.java.internal.runOnVirtualThread
import org.koaks.java.json.JacksonType

/** Adapts one Java object containing method-level @Tool annotations. */
internal fun scanAnnotatedTools(toolContainer: Any): List<ToolSpec<*>> {
    validateNonPublicAnnotatedMethods(toolContainer.javaClass)

    val annotatedMethods = toolContainer.javaClass.methods
        .asSequence()
        .filterNot { it.isBridge || it.isSynthetic }
        .mapNotNull { method -> method.getAnnotation(ToolAnnotation::class.java)?.let { method to it } }
        .sortedWith(compareBy({ it.first.declaringClass.name }, { it.first.name }, { it.first.toGenericString() }))
        .toList()

    require(annotatedMethods.isNotEmpty()) {
        "${toolContainer.javaClass.name} does not declare any public @Tool methods"
    }

    val names = mutableSetOf<String>()
    return annotatedMethods.map { (method, annotation) ->
        val toolName = annotation.name.ifBlank { method.name }
        val toolDescription = annotation.resolvedDescription
        require(toolDescription.isNotBlank()) {
            "@Tool description on ${method.displayName()} must not be blank"
        }
        require(names.add(toolName)) {
            "duplicate @Tool name '$toolName' on ${toolContainer.javaClass.name}"
        }
        require(!method.isVarArgs) { "@Tool method ${method.displayName()} must not use varargs" }
        require(method.parameterCount <= 1) {
            "@Tool method ${method.displayName()} must have zero parameters or one record/POJO parameter"
        }

        val async = CompletionStage::class.java.isAssignableFrom(method.returnType)
        require(method.returnType == String::class.java || async) {
            "@Tool method ${method.displayName()} must return String or CompletionStage<String>"
        }
        validateAsyncResultType(method, async)
        require(method.trySetAccessible()) {
            "@Tool method ${method.displayName()} cannot be accessed; " +
                "make its declaring class public or open its package to Koaks"
        }

        when {
            method.parameterCount == 0 && async -> Tools.noArgsAsync(
                toolName,
                toolDescription,
            ) { invokeAsync(toolContainer, method) }

            method.parameterCount == 0 -> Tools.noArgs(
                toolName,
                toolDescription,
            ) { invokeString(toolContainer, method) }

            else -> annotatedInputTool(toolContainer, method, toolName, toolDescription, async)
        }
    }
}

private fun annotatedInputTool(
    toolContainer: Any,
    method: Method,
    toolName: String,
    toolDescription: String,
    async: Boolean,
): ToolSpec<*> {
    val inputType = JacksonType.of(method.genericParameterTypes.single())
    if (inputType.isObjectInput()) {
        return if (async) {
            Tools.async(toolName, toolDescription, inputType) { input ->
                invokeAsync(toolContainer, method, input)
            }
        } else {
            Tools.sync(toolName, toolDescription, inputType) { input ->
                invokeString(toolContainer, method, input)
            }
        }
    }

    val parameter = method.parameters.single()
    val metadata = parameter.getAnnotation(Param::class.java)
        ?: parameter.annotatedType.getAnnotation(Param::class.java)
    requireNotNull(metadata) {
        "direct @Tool parameter on ${method.displayName()} must declare " +
            "@Param(name = \"...\", description = \"...\")"
    }
    val parameterName = metadata.name
    require(parameterName.isNotBlank()) {
        "@Param on direct @Tool parameter ${method.displayName()} must explicitly declare name"
    }
    val parameterDescription = metadata.resolvedDescription
    require(parameterDescription.isNotBlank()) {
        "@Param on direct @Tool parameter ${method.displayName()} must declare description"
    }
    val required = metadata.required

    val handler: suspend (Any?) -> String = if (async) {
        { input -> invokeAsync(toolContainer, method, input).awaitStage() }
    } else {
        { input -> runOnVirtualThread("koaks-tool-$toolName") { invokeString(toolContainer, method, input) } }
    }

    return reflectedParameterTool(
        name = toolName,
        description = toolDescription,
        inputType = inputType,
        parameterName = parameterName,
        parameterDescription = parameterDescription,
        required = required,
        handler = handler,
    )
}

private fun JacksonType<*>.isObjectInput(): Boolean =
    schema["type"]?.jsonPrimitive?.content == "object" || schema.containsKey("oneOf")

private fun reflectedParameterTool(
    name: String,
    description: String,
    inputType: JacksonType<Any?>,
    parameterName: String,
    parameterDescription: String,
    required: Boolean,
    handler: suspend (Any?) -> String,
): ToolSpec<Any?> = ToolSpec.create { direct, sideEffects ->
    RawReflectedParameterTool(
        name = name,
        description = description,
        inputType = inputType,
        parameterName = parameterName,
        parameterDescription = parameterDescription,
        required = required,
        returnDirectly = direct,
        hasSideEffects = sideEffects,
        handler = handler,
    )
}

private class RawReflectedParameterTool(
    override val name: String,
    override val description: String,
    private val inputType: JacksonType<Any?>,
    private val parameterName: String,
    parameterDescription: String,
    private val required: Boolean,
    override val returnDirectly: Boolean,
    override val hasSideEffects: Boolean,
    private val handler: suspend (Any?) -> String,
) : Tool<String> {
    override val inputSerializer = String.serializer()
    override val acceptsRawJson: Boolean = true
    override val parametersOverride: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put(parameterName, buildJsonObject {
                inputType.schema.forEach { (key, value) -> put(key, value) }
                if (parameterDescription.isNotBlank()) {
                    put("description", JsonPrimitive(parameterDescription))
                }
            })
        })
        if (required) {
            put("required", buildJsonArray { add(JsonPrimitive(parameterName)) })
        }
    }

    override suspend fun execute(input: String): String = handler(decodeParameter(input))

    private fun decodeParameter(input: String): Any? {
        val document = inputType.mapper.readTree(input.ifBlank { "{}" })
        require(document != null && document.isObject) {
            "arguments for tool '$name' must be a JSON object"
        }
        val value = document.get(parameterName)
        if (value == null || value.isNull) {
            require(!required) { "required tool parameter '$parameterName' is missing" }
            return null
        }
        return inputType.decode(inputType.mapper.writeValueAsString(value))
    }
}

private fun validateNonPublicAnnotatedMethods(type: Class<*>) {
    generateSequence(type) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .filter { it.getAnnotation(ToolAnnotation::class.java) != null }
        .firstOrNull { !Modifier.isPublic(it.modifiers) }
        ?.let { method ->
            throw IllegalArgumentException("@Tool method ${method.displayName()} must be public")
        }
}

private fun validateAsyncResultType(method: Method, async: Boolean) {
    if (!async) return
    val returnType = method.genericReturnType as? ParameterizedType ?: return
    val resultType = returnType.actualTypeArguments.singleOrNull() ?: return
    require(resultType == String::class.java) {
        "@Tool method ${method.displayName()} must return CompletionStage<String>"
    }
}

private fun invokeString(toolContainer: Any, method: Method, vararg arguments: Any?): String =
    requireNotNull(invoke(toolContainer, method, *arguments) as? String) {
        "@Tool method ${method.displayName()} returned null instead of String"
    }

@Suppress("UNCHECKED_CAST")
private fun invokeAsync(
    toolContainer: Any,
    method: Method,
    vararg arguments: Any?,
): CompletionStage<String> =
    requireNotNull(invoke(toolContainer, method, *arguments) as? CompletionStage<*>) {
        "@Tool method ${method.displayName()} returned null instead of CompletionStage<String>"
    } as CompletionStage<String>

private fun invoke(toolContainer: Any, method: Method, vararg arguments: Any?): Any? = try {
    method.invoke(toolContainer, *arguments)
} catch (failure: InvocationTargetException) {
    throw failure.targetException
}

private fun Method.displayName(): String = "${declaringClass.name}#$name"
