package org.endow.framework.websearch

interface ISearch {

    fun search(query: String): List<SearchResult>

    fun nextPage(): List<SearchResult>?

}