package org.endow.framework.utils

import com.google.gson.Gson

object JsonUtil {

    val innerJson = Gson()

    fun toJson(obj: Any): String {
        return when (obj) {
            is String -> obj
            else -> innerJson.toJson(obj)
        }
    }

    inline fun <reified T> fromJson(json: String): T {
        return innerJson.fromJson(json, T::class.java)
    }

    fun <T> fromJson(json: String?, clazz: Class<T>): T {
        return innerJson.fromJson<T>(json, clazz)
    }
}

inline fun <reified T> String.fromJson(): T =
    JsonUtil.fromJson(this)