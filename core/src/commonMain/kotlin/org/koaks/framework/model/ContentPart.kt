package org.koaks.framework.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An immutable piece of message content. Tool calls and tool results are first-class
 * [ModelItem]s, not content parts.
 */
@Serializable
sealed interface ContentPart {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    @Serializable
    @SerialName("image")
    data class Image(val url: String? = null, val base64: String? = null) : ContentPart

    @Serializable
    @SerialName("audio")
    data class Audio(val url: String? = null, val base64: String? = null, val format: String) : ContentPart
}
