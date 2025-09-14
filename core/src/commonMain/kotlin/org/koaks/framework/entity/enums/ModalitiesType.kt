package org.koaks.framework.entity.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModalitiesType {

    @SerialName("audio")
    AUDIO,

    @SerialName("text")
    TEXT,

}