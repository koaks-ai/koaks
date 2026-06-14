package org.koaks.framework.loop

import org.koaks.framework.model.ToolCall

/**
 * Assembles a single [ToolCall] from fragments that arrive across multiple stream
 * chunks (id/name/arguments delivered piecemeal). Keyed by id (or index) in the
 * accumulator.
 */
class ToolCallBuilder {
    private var id: String? = null
    private var name: StringBuilder = StringBuilder()
    private var arguments: StringBuilder = StringBuilder()

    fun mergeDelta(id: String?, nameDelta: String?, argumentsDelta: String?) {
        if (id != null && this.id == null) this.id = id
        if (nameDelta != null) name.append(nameDelta)
        if (argumentsDelta != null) arguments.append(argumentsDelta)
    }

    /** Merges an already-complete tool call (non-streaming or pre-assembled path). */
    fun mergeComplete(call: ToolCall) {
        id = call.id
        name = StringBuilder(call.name)
        arguments = StringBuilder(call.arguments)
    }

    fun build(): ToolCall = ToolCall(
        id = id ?: "",
        name = name.toString(),
        arguments = arguments.toString().ifBlank { "{}" },
    )
}
