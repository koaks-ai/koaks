package org.koaks.framework.tool

import kotlinx.serialization.KSerializer

/**
 * A [Tool] built from a lambda. Backs the inline DSL `tool<In>(name, description) { ... }`.
 */
class InlineTool<In>(
    override val name: String,
    override val description: String,
    override val inputSerializer: KSerializer<In>,
    override val returnDirectly: Boolean = false,
    override val hasSideEffects: Boolean = false,
    private val block: suspend (In) -> String,
) : Tool<In> {
    override suspend fun execute(input: In): String = block(input)
}
