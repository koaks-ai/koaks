package org.koaks.framework.utils.multimodal

expect object Base64Util {
    fun encode(path: String): String
    fun decode(base64: String, path: String)
}