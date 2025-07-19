package org.endow.framework.websearch

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class BingSearch : ISearch {

    val logger = KotlinLogging.logger {}

    private var currentQuery: String = ""
    private var currentPage: Int = 0
    private val pageSize = 10

    override fun search(query: String): List<SearchResult> {
        currentQuery = query
        currentPage = 1
        return fetchPage(currentQuery)
    }

    override fun nextPage(): List<SearchResult>? {
        logger.warn { " nextPage not implemented" }
        return emptyList()
    }

    private fun fetchPage(query: String): List<SearchResult> {
        val url = "https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}"

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .referrer("https://www.bing.com/")
            .timeout(10000)
            .get()

        val results = doc.select("ol#b_results > li.b_algo")
        if (results.isEmpty()) return emptyList()

        return results.mapIndexed { idx, li ->
            SearchResult(
                index = idx + 1,
                title = li.selectFirst("h2 a")?.text()?.trim() ?: "无标题",
                url = li.selectFirst("h2 a")?.absUrl("href") ?: "",
                cite = li.selectFirst("cite")?.text()?.trim() ?: "",
                snippet = li.selectFirst(".b_caption p")?.text()?.trim() ?: ""
            )
        }
    }

    fun viewPageContent(url: String): WebPageResult {
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .referrer("https://www.bing.com/")
                .timeout(10000)
                .get()

            val title = doc.title()
            val bodyText = doc.body().text()
            return WebPageResult(title, bodyText)
        } catch (e: Exception) {
            return WebPageResult("unable to load page", "unable to load page content：${e.message}")
        }
    }
}
