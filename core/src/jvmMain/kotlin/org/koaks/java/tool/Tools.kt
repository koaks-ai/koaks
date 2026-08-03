package org.koaks.java.tool

import java.util.concurrent.CompletionStage
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.tool.Tool
import org.koaks.java.internal.awaitStage
import org.koaks.java.internal.runOnVirtualThread
import org.koaks.java.json.JacksonType
import tools.jackson.core.type.TypeReference

/** A checked, blocking Java tool callback. It is always executed on a virtual thread. */
fun interface SyncToolHandler<T> {
    @Throws(Exception::class)
    fun execute(input: T): String
}

/** An asynchronous Java tool callback. */
fun interface AsyncToolHandler<T> {
    fun execute(input: T): CompletionStage<String>
}

fun interface NoArgsToolHandler {
    @Throws(Exception::class)
    fun execute(): String
}

fun interface AsyncNoArgsToolHandler {
    fun execute(): CompletionStage<String>
}

/** Immutable Java-facing tool definition. */
class ToolSpec<T> private constructor(
    private val factory: (returnDirectly: Boolean, hasSideEffects: Boolean) -> Tool<*>,
    private val direct: Boolean = false,
    private val sideEffects: Boolean = false,
) {
    fun returnDirectly(): ToolSpec<T> = ToolSpec(factory, direct = true, sideEffects = sideEffects)

    fun sideEffecting(): ToolSpec<T> = ToolSpec(factory, direct = direct, sideEffects = true)

    internal fun toCoreTool(): Tool<*> = factory(direct, sideEffects)

    companion object {
        internal fun <T> create(
            factory: (returnDirectly: Boolean, hasSideEffects: Boolean) -> Tool<*>,
        ): ToolSpec<T> = ToolSpec(factory)
    }
}

/** Java-friendly factories for typed and no-argument tools. */
object Tools {
    @JvmStatic
    fun <T> sync(
        name: String,
        description: String,
        inputType: Class<T>,
        handler: SyncToolHandler<T>,
    ): ToolSpec<T> = sync(name, description, JacksonType.of(inputType), handler)

    @JvmStatic
    fun <T> sync(
        name: String,
        description: String,
        inputType: TypeReference<T>,
        handler: SyncToolHandler<T>,
    ): ToolSpec<T> = sync(name, description, JacksonType.of(inputType), handler)

    @JvmStatic
    fun <T> sync(
        name: String,
        description: String,
        inputType: JacksonType<T>,
        handler: SyncToolHandler<T>,
    ): ToolSpec<T> = ToolSpec.create(factory = { direct, sideEffects ->
        RawJacksonTool(name, description, inputType, direct, sideEffects) { input ->
            runOnVirtualThread("koaks-tool-$name") { handler.execute(input) }
        }
    })

    @JvmStatic
    fun <T> async(
        name: String,
        description: String,
        inputType: Class<T>,
        handler: AsyncToolHandler<T>,
    ): ToolSpec<T> = async(name, description, JacksonType.of(inputType), handler)

    @JvmStatic
    fun <T> async(
        name: String,
        description: String,
        inputType: TypeReference<T>,
        handler: AsyncToolHandler<T>,
    ): ToolSpec<T> = async(name, description, JacksonType.of(inputType), handler)

    @JvmStatic
    fun <T> async(
        name: String,
        description: String,
        inputType: JacksonType<T>,
        handler: AsyncToolHandler<T>,
    ): ToolSpec<T> = ToolSpec.create(factory = { direct, sideEffects ->
        RawJacksonTool(name, description, inputType, direct, sideEffects) { input ->
            requireNotNull(handler.execute(input)) { "async tool '$name' returned a null CompletionStage" }.awaitStage()
        }
    })

    @JvmStatic
    fun noArgs(
        name: String,
        description: String,
        handler: NoArgsToolHandler,
    ): ToolSpec<Void> = ToolSpec.create(factory = { direct, sideEffects ->
        RawNoArgsTool(name, description, direct, sideEffects) {
            runOnVirtualThread("koaks-tool-$name") { handler.execute() }
        }
    })

    @JvmStatic
    fun noArgsAsync(
        name: String,
        description: String,
        handler: AsyncNoArgsToolHandler,
    ): ToolSpec<Void> = ToolSpec.create(factory = { direct, sideEffects ->
        RawNoArgsTool(name, description, direct, sideEffects) {
            requireNotNull(handler.execute()) { "async tool '$name' returned a null CompletionStage" }.awaitStage()
        }
    })
}

private class RawJacksonTool<T>(
    override val name: String,
    override val description: String,
    private val inputType: JacksonType<T>,
    override val returnDirectly: Boolean,
    override val hasSideEffects: Boolean,
    private val handler: suspend (T) -> String,
) : Tool<String> {
    init {
        require(inputType.schema["type"]?.toString()?.trim('"') == "object" || inputType.schema.containsKey("oneOf")) {
            "tool input '$name' must describe a JSON object"
        }
    }

    override val inputSerializer = String.serializer()
    override val parametersOverride: JsonObject = inputType.schema
    override val acceptsRawJson: Boolean = true

    override suspend fun execute(input: String): String = handler(inputType.decode(input))
}

private class RawNoArgsTool(
    override val name: String,
    override val description: String,
    override val returnDirectly: Boolean,
    override val hasSideEffects: Boolean,
    private val handler: suspend () -> String,
) : Tool<String> {
    override val inputSerializer = String.serializer()
    override val parametersOverride: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(emptyMap()))
    }
    override val acceptsRawJson: Boolean = true

    override suspend fun execute(input: String): String = handler()
}
