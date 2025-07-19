package org.endow.framework.websearch

import org.endow.framework.annotation.Param
import org.endow.framework.annotation.Tool
import org.endow.framework.utils.JsonUtil

class WebSearch {

    private val searchEngine = BingSearch()

    @Tool(
        description = "Web Search",
        params = [
            Param(param = "query", description = "Search query keyword")
        ],
        group = "websearch"
    )
    fun websearch(query: String): String {
        return JsonUtil.toJson(searchEngine.search(query))
    }

    @Tool(
        description = "view the detailed information of a webpage",
        params = [
            Param(param = "url", description = "the url of the webpage that needs to be viewed")
        ],
        group = "websearch"
    )
    fun webPageViewer(url: String): String {
        return JsonUtil.toJson(searchEngine.viewPageContent(url))
    }

}