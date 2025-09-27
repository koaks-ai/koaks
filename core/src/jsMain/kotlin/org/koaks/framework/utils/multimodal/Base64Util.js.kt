package org.koaks.framework.utils.multimodal

actual object Base64Util {
    actual fun encode(path: String): String {
        throw NotImplementedError("js platform encode not implemented")
    }

    actual fun decode(base64: String, path: String) {
        throw NotImplementedError("js platform decode not implemented")
    }
}