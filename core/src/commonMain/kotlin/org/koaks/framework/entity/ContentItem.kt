package org.koaks.framework.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ContentItem {

    /**
     * Text content
     */
    @Serializable
    @SerialName("text")
    data class Text(val text: String?) : ContentItem()

    /**
     * Image content
     */
    @Serializable
    @SerialName("image")
    data class Image(
        @SerialName("image")
        val url: String
    ) : ContentItem()

    /**
     * Video content, represented as a list of frames/segments
     */
    @Serializable
    @SerialName("video")
    data class Video(
        val video: List<String>
    ) : ContentItem()

}