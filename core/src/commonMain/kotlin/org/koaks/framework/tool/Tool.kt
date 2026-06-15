package org.koaks.framework.tool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * A callable unit of functionality an agent can invoke.
 *
 * Design §3.5: tools keep a single typed input parameter [In]; [execute] returns
 * the string fed back to the model. Tools needing structured results serialize
 * them inside [execute] themselves.
 *
 * @param In the structured input type, decoded from the model's raw JSON arguments
 *   via [inputSerializer].
 */
interface Tool<In> {
    /** Locally-unique identifier the model uses to select this tool. */
    val name: String

    /** Natural-language description; critical for invocation accuracy. */
    val description: String

    /** Serializer used to decode the model's JSON arguments into [In] and to derive the schema. */
    val inputSerializer: KSerializer<In>

    /** When true, the agent loop finishes immediately after this tool succeeds. */
    val returnDirectly: Boolean get() = false

    /** When true, this tool has external side effects (email/charge/db). Affects rollback semantics (§4.5). */
    val hasSideEffects: Boolean get() = false

    /**
     * Optional pre-built JSON Schema for the parameters. When non-null it is used
     * verbatim instead of deriving one from [inputSerializer] — needed for tools
     * whose schema is supplied externally (e.g. MCP `tools/list`).
     */
    val parametersOverride: JsonObject? get() = null

    /**
     * When true, the registry skips JSON decoding and passes the model's raw
     * arguments string directly to [execute] (so [In] must be `String`). Used by
     * passthrough tools like MCP adapters that forward arguments verbatim.
     */
    val acceptsRawJson: Boolean get() = false

    /** Performs the tool logic. The returned string is fed back to the model. */
    suspend fun execute(input: In): String
}
