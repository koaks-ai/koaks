package org.koaks.framework.model

/**
 * Provider-neutral citation / file / URL annotation on assistant text.
 * Unknown annotation kinds are preserved as [Generic].
 */
sealed interface Annotation {
    data class UrlCitation(
        val url: String,
        val title: String? = null,
        val startIndex: Int? = null,
        val endIndex: Int? = null,
    ) : Annotation

    data class FileCitation(
        val fileId: String,
        val filename: String? = null,
        val startIndex: Int? = null,
        val endIndex: Int? = null,
    ) : Annotation

    data class Generic(
        val kind: String,
        val payload: String,
    ) : Annotation
}
