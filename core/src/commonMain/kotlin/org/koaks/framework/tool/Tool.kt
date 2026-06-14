package org.koaks.framework.tool

import kotlinx.serialization.KSerializer

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

    /** Performs the tool logic. The returned string is fed back to the model. */
    suspend fun execute(input: In): String
}
