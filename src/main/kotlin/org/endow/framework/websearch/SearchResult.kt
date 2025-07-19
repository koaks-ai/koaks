package org.endow.framework.websearch

data class SearchResult(
    val index: Int,
    val title: String,
    val url: String,
    val cite: String,
    val snippet: String
)

data class WebPageResult(
    val title: String,
    val content: String,
)