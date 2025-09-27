package org.koaks.framework.utils.multimodal

import java.io.File
import java.util.Base64

actual object Base64Util {
    actual fun encode(path: String): String {
        val bytes = File(path).readBytes()
        return Base64.getEncoder().encodeToString(bytes)
    }

    actual fun decode(base64: String, path: String) {
        val bytes = Base64.getDecoder().decode(base64)
        File(path).writeBytes(bytes)
    }
}