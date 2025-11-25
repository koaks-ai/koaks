package org.koaks.graph

data class EngineRunnableConfig(
    val maxIterations: Int = 256,
    val enableValidation: Boolean = true
)