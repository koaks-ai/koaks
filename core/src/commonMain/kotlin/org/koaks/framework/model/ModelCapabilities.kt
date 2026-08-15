package org.koaks.framework.model

/**
 * Declares model-specific capabilities. The framework maintains no "model → capability"
 * table: values come from the provider DSL (with [Support.Unknown] as the honest default
 * for anything not declared). Prepare rejects only [Support.Unsupported] combinations.
 *
 * Streaming and tool calling are request parameters, not capability gates.
 */
data class ModelCapabilities(
    val parallelToolCalls: Support = Support.Supported,
    val vision: Support = Support.Unknown,
    val jsonObject: Support = Support.Unknown,
    val jsonSchema: Support = Support.Unknown,
    val assistantPrefill: Support = Support.Unknown,
) {
    companion object {
        val DEFAULT = ModelCapabilities()
    }
}

fun ModelCapabilities.rejectIfUnsupported(feature: String, support: Support, path: String) {
    if (support.isKnownUnsupported) {
        throw AgentFrameworkException(
            AgentError.PreparationError(
                component = "capabilities",
                message = "$feature is not supported by this model (path: $path)",
            ),
        )
    }
}
