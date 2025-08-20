package org.koaks.framework.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object JsonUtil {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        prettyPrint = false
    }

    inline fun <reified T> toJson(obj: T): String {
        return when (obj) {
            is String -> obj
            else -> json.encodeToString(serializer(), obj)
        }
    }

    inline fun <reified T> fromJson(jsonStr: String?): T {
        if (jsonStr == null) {
            return json.decodeFromString(serializer(), "{}")
        }
        return json.decodeFromString(serializer(), jsonStr)
    }

    fun <T> fromJson(jsonStr: String?, deserializer: KSerializer<T>): T {
        return if (jsonStr == null) {
            json.decodeFromString(deserializer, "{}")
        } else {
            json.decodeFromString(deserializer, jsonStr)
        }
    }
}
