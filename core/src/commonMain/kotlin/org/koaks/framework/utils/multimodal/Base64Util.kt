package org.koaks.framework.utils.multimodal

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 transform for multimodal payloads (e.g. the `base64` field of
 * [org.koaks.framework.model.ContentPart.Image] / `Audio`).
 *
 * This is a pure byte transform and lives entirely in `commonMain` — file reading
 * is platform-specific (and meaningless in the browser), so it stays the caller's
 * concern: pass the bytes you already hold.
 */
@OptIn(ExperimentalEncodingApi::class)
object Base64Util {
    /** Encodes raw bytes to a standard (RFC 4648) Base64 string. */
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    /** Decodes a standard (RFC 4648) Base64 string back to raw bytes. */
    fun decode(base64: String): ByteArray = Base64.decode(base64)
}
