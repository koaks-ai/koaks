package org.koaks.framework.model

/**
 * Three-state capability flag. Prepare rejects only [Unsupported] combinations;
 * [Unknown] is allowed through so third-party compatible endpoints are not
 * false-rejected, and a runtime failure is reported precisely.
 */
enum class Support {
    Supported,
    Unsupported,
    Unknown,
    ;

    val isKnownUnsupported: Boolean get() = this == Unsupported
    val isKnownSupported: Boolean get() = this == Supported
}
