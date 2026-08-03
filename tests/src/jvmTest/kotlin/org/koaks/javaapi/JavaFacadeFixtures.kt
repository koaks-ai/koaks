package org.koaks.javaapi

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage

object JavaFacadeFixtures {
    @JvmStatic
    fun textModel(text: String): LanguageModel = ScriptedModel(
        listOf(ModelEvent.TextDelta(text), ModelEvent.Completed(Usage.ZERO)),
    )

    @JvmStatic
    fun structuredModel(json: String): LanguageModel = ScriptedModel(
        listOf(ModelEvent.TextDelta("draft"), ModelEvent.Completed(Usage.ZERO)),
        listOf(ModelEvent.TextDelta(json), ModelEvent.Completed(Usage.ZERO)),
    )

    @JvmStatic
    fun toolModel(toolName: String, argumentsJson: String, finalText: String): LanguageModel = ScriptedModel(
        listOf(
            ModelEvent.ToolCallCompleted(ToolCall("call-1", toolName, argumentsJson)),
            ModelEvent.Completed(Usage.ZERO),
        ),
        listOf(ModelEvent.TextDelta(finalText), ModelEvent.Completed(Usage.ZERO)),
    )

    @JvmStatic
    fun describedToolModel(
        toolName: String,
        argumentsJson: String,
        parameterName: String,
        parameterDescription: String,
        finalText: String,
    ): LanguageModel = object : LanguageModel {
        private var invocation = 0
        override val capabilities: ModelCapabilities = ModelCapabilities()

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            if (invocation++ == 0) {
                val tool = request.tools.single { it.name == toolName }
                val property = tool.parameters["properties"]
                    ?.jsonObject
                    ?.get(parameterName)
                    ?.jsonObject
                    ?: error("missing schema property '$parameterName'")
                check(property["description"]?.jsonPrimitive?.content == parameterDescription) {
                    "unexpected description for '$parameterName': ${property["description"]}"
                }
                emit(ModelEvent.ToolCallCompleted(ToolCall("call-1", toolName, argumentsJson)))
                emit(ModelEvent.Completed(Usage.ZERO))
            } else {
                emit(ModelEvent.TextDelta(finalText))
                emit(ModelEvent.Completed(Usage.ZERO))
            }
        }
    }

    @JvmStatic
    fun neverCompletingModel(): LanguageModel = object : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow { awaitCancellation() }
    }
}

private class ScriptedModel(
    vararg scripts: List<ModelEvent>,
) : LanguageModel {
    private val remaining = ArrayDeque(scripts.toList())
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        val events = if (remaining.isEmpty()) emptyList() else remaining.removeFirst()
        events.forEach { emit(it) }
    }
}
