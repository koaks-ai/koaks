package org.koaks.framework.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean = true,
)