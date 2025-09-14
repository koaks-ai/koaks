package org.koaks.framework.entity

import kotlinx.serialization.Serializable

@Serializable
data class AudioConfig(
    val voice: String,
    val format: String
)