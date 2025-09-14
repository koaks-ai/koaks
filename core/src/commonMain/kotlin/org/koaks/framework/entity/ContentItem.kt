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
     * Url Image content
     */
    @Serializable
    @SerialName("image_url")
    data class Image(
        @SerialName("image_url")
        val imagePath: Url
    ) : ContentItem() {
        @Serializable
        data class Url(
            val url: String
        )
    }

    /**
     * Audio content
     */
    @Serializable
    @SerialName("input_audio")
    data class InputAudio(
        @SerialName("input_audio")
        val audioContent: AudioContent
    ) : ContentItem() {
        @Serializable
        data class AudioContent(
            val data: String,
            val format: String,
        )
    }

    /**
     * Video content, represented as a list of frames/segments
     */
    @Serializable
    @SerialName("video")
    data class VideoFrame(
        val video: List<String>
    ) : ContentItem()

    @Serializable
    @SerialName("video_url")
    data class VideoUrl(
        @SerialName("video_url")
        val url: Url
    ) : ContentItem() {
        @Serializable
        data class Url(
            val url: String
        )
    }

}