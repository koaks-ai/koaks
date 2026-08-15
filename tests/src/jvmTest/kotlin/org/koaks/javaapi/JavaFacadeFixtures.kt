package org.koaks.javaapi

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.loop.done
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage

object JavaFacadeFixtures {
    @JvmStatic
    fun textModel(text: String): LanguageModel = ScriptedModel(
        listOf(ModelEvent.TextDelta(text), done(Usage.ZERO)),
    )

    @JvmStatic
    fun structuredModel(json: String): LanguageModel = ScriptedModel(
        listOf(ModelEvent.TextDelta("draft"), done(Usage.ZERO)),
        listOf(ModelEvent.TextDelta(json), done(Usage.ZERO)),
    )

    @JvmStatic
    fun toolModel(toolName: String, argumentsJson: String, finalText: String): LanguageModel = ScriptedModel(
        listOf(
            ModelEvent.ToolCallCompleted(ToolCall("call-1", toolName, argumentsJson)),
            done(Usage.ZERO),
        ),
        listOf(ModelEvent.TextDelta(finalText), done(Usage.ZERO)),
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

        override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
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
                emit(done(Usage.ZERO))
            } else {
                emit(ModelEvent.TextDelta(finalText))
                emit(done(Usage.ZERO))
            }
        }
    }

    @JvmStatic
    fun neverCompletingModel(): LanguageModel = object : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        override fun stream(request: ModelRequest): Flow<ModelEvent> = flow { awaitCancellation() }
    }
}

private class ScriptedModel(
    vararg scripts: List<ModelEvent>,
) : LanguageModel {
    private val remaining = ArrayDeque(scripts.toList())
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val events = if (remaining.isEmpty()) emptyList() else remaining.removeFirst()
        events.forEach { emit(it) }
    }
}
