package org.koaks.framework.utils.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import org.koaks.framework.entity.ContentItem

object ContentListSerializer : KSerializer<List<ContentItem>> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ContentList")

    override fun deserialize(decoder: Decoder): List<ContentItem> {
        val input = decoder as? JsonDecoder ?: error("JsonDecoder expected")
        return when (val element = input.decodeJsonElement()) {
            // the server returns a string → wrap it as Text
            is JsonPrimitive -> listOf(ContentItem.Text(element.content))
            is JsonArray -> element.map { Json.decodeFromJsonElement(ContentItem.serializer(), it) }
            else -> error("Unexpected JSON for content: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: List<ContentItem>) {
        val output = encoder as? JsonEncoder ?: error("JsonEncoder expected")

        // if there is only one Text, serialize it into a String (to meet the server's requirements).
        if (value.size == 1 && value.first() is ContentItem.Text) {
            val text = (value.first() as ContentItem.Text).text
            text?.let {
                output.encodeString(text)
            }
        } else {
            // otherwise, serialize into an array (multimodal request).
            output.encodeSerializableValue(ListSerializer(ContentItem.serializer()), value)
        }
    }
}