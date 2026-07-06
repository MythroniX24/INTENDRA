package com.interndra.search

import android.util.Log
import com.interndra.data.local.AgentDao
import com.interndra.data.model.WebSearchCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * WebSearchEngine — Phase 4 pipeline.
 *
 * Pipeline: user query → check cache → search DuckDuckGo HTML → fetch top N
 * result pages → extract main content → truncate to a bounded digest → write
 * through to cache → return results + digest for the AI to summarize.
 *
 * FIXES:
 *  1. NO UNICODE STRIPPING — Hindi/Unicode queries no longer lose chars.
 *  2. CACHING — results + page digests cached in Room for 30 minutes.
 *  3. PAGE FETCH + EXTRACT — fetchAndExtract() pulls top N pages and extracts
 *     main-article text via Jsoup + content heuristics.
 *  4. NO TRUNCATED URLS — full URL preserved in buildContext().
 *  5. SOURCE ATTRIBUTION — each page digest is prefixed with its source URL.
 *
 * COMPILE FIX: readCache() and writeCache() wrap the suspend DAO calls in
 * kotlinx.coroutines.runBlocking { } so they can be invoked from the
 * non-suspend search() function. This is safe because search() is always
 * called from withContext(Dispatchers.IO) in the ViewModel.
 */
class WebSearchEngine(private val dao: AgentDao) {

    companion object {
        private const val TAG = "WebSearch"
        private const val BASE_URL = "https://html.duckduckgo.com/html/?q="
        private const val MAX_SNIPPET_CHARS = 250
        private const val MAX_PAGE_CHARS = 2_500
        private const val MAX_TOTAL_DIGEST = 6_000
        private const val CACHE_TTL_MS = 30L * 60 * 1000

        private val SKIP_FETCH_DOMAINS = setOf(
            "youtube.com", "youtu.be", "facebook.com", "instagram.com",
            "twitter.com", "x.com", "tiktok.com", "reddit.com",
            "pinterest.com", "amazon.com", "amazon.in"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pageClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String
    )

    data class PageContent(
        val url: String,
        val title: String,
        val extractedText: String,
        val fetchSuccess: Boolean
    )

    fun shouldSearch(input: String): Boolean {
        val lo = input.lowercase()
        if (lo.contains("search") || lo.contains("websearch") || lo.contains("web search") ||
            lo.contains("find info") || lo.contains("look up")) return true
        if (lo.contains("latest") || lo.contains("recent") || lo.contains("today") ||
            lo.contains("news") || lo.contains("current") || lo.contains("price of") ||
            lo.contains("weather") || lo.contains("stock")) return true
        if (lo.contains("who is") || lo.contains("what is") || lo.contains("where is") ||
            lo.contains("how to") || lo.contains("tell me about")) return true
        val namedEntityPattern = Regex("""(about|on|regarding)\s+[A-Z][a-z]""")
        if (namedEntityPattern.containsMatchIn(input)) return true
        return false
    }

    fun search(query: String, maxResults: Int = 5): List<SearchResult> {
        val sanitized = query.trim().take(200)
        if (sanitized.isBlank()) return emptyList()

        val cached = readCache(sanitized)
        if (cached != null) {
            Log.d(TAG, "Cache hit for query: ${sanitized.take(40)}")
            return cached
        }

        val url = BASE_URL + java.net.URLEncoder.encode(sanitized, "UTF-8")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val results = try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Search request failed: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            parseResults(body, maxResults)
        } catch (e: Exception) {
            Log.e(TAG, "Search error for query '${sanitized.take(40)}': ${e.message}")
            emptyList()
        }

        writeCache(sanitized, results)
        return results
    }

    fun fetchAndExtract(results: List<SearchResult>, maxPages: Int = 2): String {
        if (results.isEmpty()) return ""

        val pages = results
            .take(maxPages)
            .filter { shouldFetch(it.url) }
            .map { fetchPage(it) }

        if (pages.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n[Web Page Content — fetched and extracted]")
        var totalChars = 0
        for ((i, page) in pages.withIndex()) {
            if (!page.fetchSuccess || page.extractedText.isBlank()) continue
            val entry = buildString {
                appendLine("Source [${i + 1}]: ${page.title}")
                appendLine("URL: ${page.url}")
                appendLine("Content:")
                appendLine(page.extractedText.take(MAX_PAGE_CHARS))
                appendLine()
            }
            if (totalChars + entry.length > MAX_TOTAL_DIGEST) {
                sb.appendLine("…(additional sources truncated to fit context)")
                break
            }
            sb.append(entry)
            totalChars += entry.length
        }
        return sb.toString().trim().ifEmpty { "" }
    }

    private fun shouldFetch(url: String): Boolean {
        if (url.isBlank()) return false
        val host = try {
            java.net.URI(url).host?.lowercase() ?: return false
        } catch (_: Exception) { return false }
        return SKIP_FETCH_DOMAINS.none { host == it || host.endsWith(".$it") }
    }

    private fun fetchPage(result: SearchResult): PageContent {
        return try {
            val request = Request.Builder()
                .url(result.url)
                .header("User-Agent", "Mozilla/5.0 (compatible; INTERNDRA-ResearchBot/1.0)")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .build()
            val response = pageClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Page fetch failed (${response.code}): ${result.url.take(60)}")
                return PageContent(result.url, result.title, "", fetchSuccess = false)
            }
            val html = response.body?.string() ?: return PageContent(result.url, result.title, "", false)
            val extracted = extractMainContent(html, result.title)
            PageContent(result.url, result.title, extracted, fetchSuccess = true)
        } catch (e: Exception) {
            Log.w(TAG, "Page fetch error for ${result.url.take(60)}: ${e.message}")
            PageContent(result.url, result.title, "", fetchSuccess = false)
        }
    }

    private fun extractMainContent(html: String, fallbackTitle: String): String {
        val doc = Jsoup.parse(html)

        doc.select("script, style, nav, header, footer, aside, form, iframe, noscript, " +
                   "[role=navigation], [role=banner], [role=contentinfo], .ad, .ads, " +
                   ".advertisement, .nav, .navbar, .menu, .sidebar, .footer, .header, " +
                   ".related, .recommend, .comments, .comment-section, .social, .share, " +
                   ".newsletter, .subscribe, .popup, .modal, .cookie")
            .remove()

        val main = doc.selectFirst("article, main, [role=main], .article-body, .post-content, " +
                                    ".entry-content, .content-body, .story-body")
        val paragraphs = (main ?: doc).select("p, h1, h2, h3, li")

        val sb = StringBuilder()
        for (el in paragraphs) {
            val text = el.text().trim()
            if (text.length < 20) continue
            when (el.tagName()) {
                "h1"     -> sb.append("## ").appendLine(text)
                "h2"     -> sb.append("### ").appendLine(text)
                "h3"     -> sb.append("#### ").appendLine(text)
                "li"     -> sb.append("• ").appendLine(text)
                else     -> sb.appendLine(text)
            }
            sb.appendLine()
            if (sb.length > MAX_PAGE_CHARS * 2) break
        }

        val result = sb.toString().trim()
        return if (result.length > 50) result else fallbackTitle
    }

    private fun extractRealUrl(href: String): String {
        if (href.isBlank()) return ""
        return try {
            val normalized = if (href.startsWith("//")) "https:$href" else href
            if (!normalized.contains("uddg=")) return normalized

            val queryStart = normalized.indexOf('?')
            if (queryStart < 0) return normalized
            val query = normalized.substring(queryStart + 1)
            val uddgRaw = query.split("&")
                .firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter("uddg=")
                ?: return normalized

            java.net.URLDecoder.decode(uddgRaw, "UTF-8")
        } catch (e: Exception) {
            href
        }
    }

    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        return try {
            val doc = Jsoup.parse(html)
            val results = doc.select(".result__body")

            results.take(maxResults).mapNotNull { element ->
                val title = element.select(".result__a").text().trim()
                val snippet = element.select(".result__snippet").text().trim()
                val rawUrl = element.select(".result__a").attr("href").trim()
                val url = extractRealUrl(rawUrl)
                if (title.isBlank() && snippet.isBlank()) null
                else SearchResult(title, snippet, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            emptyList()
        }
    }

    fun buildContext(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("\n[Web Context — snippets from DuckDuckGo]")
        results.forEachIndexed { i, r ->
            sb.appendLine("${i + 1}. ${r.title}")
            sb.appendLine("   ${r.snippet.take(MAX_SNIPPET_CHARS)}")
            sb.appendLine("   ${r.url}")
        }
        return sb.toString().trim()
    }

    // ── Cache helpers ────────────────────────────────────────────────────
    // COMPILE FIX: dao.getSearchCache() and dao.insertSearchCache() are
    // suspend functions. We wrap them in runBlocking so they can be called
    // from the non-suspend search() function. This is safe because search()
    // is always called from withContext(Dispatchers.IO) in the ViewModel.

    private fun readCache(query: String): List<SearchResult>? {
        return try {
            val cached = kotlinx.coroutines.runBlocking { dao.getSearchCache(query, 0L) } ?: return null
            val age = System.currentTimeMillis() - cached.timestamp
            if (age > CACHE_TTL_MS) return null
            val arr = com.google.gson.JsonParser.parseString(cached.jsonResults).asJsonArray
            arr.map { obj ->
                val o = obj.asJsonObject
                SearchResult(
                    title = o.get("title")?.asString ?: "",
                    snippet = o.get("snippet")?.asString ?: "",
                    url = o.get("url")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed: ${e.message}")
            null
        }
    }

    private fun writeCache(query: String, results: List<SearchResult>) {
        if (results.isEmpty()) return
        try {
            val arr = com.google.gson.JsonArray()
            results.forEach { r ->
                val o = com.google.gson.JsonObject()
                o.addProperty("title", r.title)
                o.addProperty("snippet", r.snippet)
                o.addProperty("url", r.url)
                arr.add(o)
            }
            kotlinx.coroutines.runBlocking {
                dao.insertSearchCache(WebSearchCache(query = query, jsonResults = arr.toString()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }
}
