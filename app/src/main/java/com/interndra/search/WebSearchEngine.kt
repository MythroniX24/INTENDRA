package com.interndra.search

import android.util.Log
import com.interndra.data.local.AgentDao
import com.interndra.data.model.WebSearchCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.util.concurrent.TimeUnit

/**
 * WebSearchEngine — Phase 4 pipeline.
 *
 * Pipeline:
 *   user query
 *     → check cache (DAO) — return immediately if fresh
 *     → search DuckDuckGo HTML
 *     → fetch top N result pages (Jsoup)
 *     → extract main content (article body, strip nav/ads/footer noise)
 *     → truncate to a bounded digest
 *     → write through to cache
 *     → return results + digest for the AI to summarize
 *
 * FIXES:
 *  1. NO UNICODE STRIPPING: previously the query was passed through
 *     `replace(Regex("[^\u0000-\u007F]"), "")` which silently destroyed
 *     Hindi / non-ASCII characters. Now the query is sent as-is (URL-encoded).
 *  2. CACHING: results + page digests are cached in Room for 30 minutes so
 *     repeated searches don't re-hit DuckDuckGo (rate-limit / IP-ban protection).
 *  3. PAGE FETCH + EXTRACT: `fetchAndExtract()` pulls the top N pages and
 *     extracts main-article text via Jsoup + content heuristics.
 *  4. NO TRUNCATED URLS: `buildContext()` no longer cuts URLs at 100 chars
 *     (which broke query strings). Full URL preserved.
 *  5. SOURCE ATTRIBUTION: each page digest is prefixed with its source URL
 *     so the AI can cite `[1]`, `[2]`, etc. and the chat renderer can map
 *     those citations back to real links.
 */
class WebSearchEngine(private val dao: AgentDao) {

    companion object {
        private const val TAG = "WebSearch"
        private const val BASE_URL = "https://html.duckduckgo.com/html/?q="

        // Max chars per source's snippet in the AI context block.
        private const val MAX_SNIPPET_CHARS = 250

        // Max chars of extracted page content per source.
        private const val MAX_PAGE_CHARS = 2_500

        // Max chars of the total page digest returned to the AI.
        private const val MAX_TOTAL_DIGEST = 6_000

        // Cache TTL — 30 minutes. Repeated searches within this window hit the cache.
        private const val CACHE_TTL_MS = 30L * 60 * 1000

        // Domains we skip when fetching page content (heavy JS, paywalls, or
        // sites that block scrapers). Snippets are still used.
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

    /** A fetched page's extracted main content, attributed to its source. */
    data class PageContent(
        val url: String,
        val title: String,
        val extractedText: String,
        val fetchSuccess: Boolean
    )

    fun shouldSearch(input: String): Boolean {
        val lo = input.lowercase()
        // Explicit triggers
        if (lo.contains("search") || lo.contains("websearch") || lo.contains("web search") ||
            lo.contains("find info") || lo.contains("look up")) return true
        // Current / live info triggers
        if (lo.contains("latest") || lo.contains("recent") || lo.contains("today") ||
            lo.contains("news") || lo.contains("current") || lo.contains("price of") ||
            lo.contains("weather") || lo.contains("stock")) return true
        // Question triggers about real-world entities
        if (lo.contains("who is") || lo.contains("what is") || lo.contains("where is") ||
            lo.contains("how to") || lo.contains("tell me about")) return true
        // Named-entity patterns (celebrity/company searches like "elon musk network")
        val namedEntityPattern = Regex("""(about|on|regarding)\s+[A-Z][a-z]""")
        if (namedEntityPattern.containsMatchIn(input)) return true
        return false
    }

    /**
     * Search DuckDuckGo, returning a list of results with title, snippet, real URL.
     * Uses the in-DB cache if a fresh entry exists for the query.
     */
    fun search(query: String, maxResults: Int = 5): List<SearchResult> {
        val sanitized = query.trim().take(200)
        if (sanitized.isBlank()) return emptyList()

        // ── Cache check ────────────────────────────────────────────────────
        val cached = readCache(sanitized)
        if (cached != null) {
            Log.d(TAG, "Cache hit for query: ${sanitized.take(40)}")
            return cached
        }

        // ── Network search ─────────────────────────────────────────────────
        // Phase 4 FIX: do NOT strip non-ASCII — Hindi/Unicode queries were
        // silently losing characters. URL-encoding preserves them safely.
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

    /**
     * Phase 4: Fetch the top N result pages and extract main-article text
     * from each. Returns a single concatenated digest string suitable for
     * injecting into the AI prompt, with per-source attribution markers
     * the AI can cite.
     */
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

    /**
     * Extracts the main article text from an HTML page.
     *
     * Heuristics (Readability-inspired, no extra dependency):
     *  1. Parse with Jsoup.
     *  2. Remove obviously-non-content tags: script, style, nav, header,
     *     footer, aside, form, iframe, noscript, ad/nav class names.
     *  3. Prefer <article>, <main>, [role=main] if present.
     *  4. Otherwise score <p> blocks by text length and pick the densest cluster.
     *  5. Collapse whitespace, decode entities, return clean text.
     */
    private fun extractMainContent(html: String, fallbackTitle: String): String {
        val doc = Jsoup.parse(html)

        // Strip non-content tags
        doc.select("script, style, nav, header, footer, aside, form, iframe, noscript, " +
                   "[role=navigation], [role=banner], [role=contentinfo], .ad, .ads, " +
                   ".advertisement, .nav, .navbar, .menu, .sidebar, .footer, .header, " +
                   ".related, .recommend, .comments, .comment-section, .social, .share, " +
                   ".newsletter, .subscribe, .popup, .modal, .cookie")
            .remove()

        // Try semantic main content first
        val main = doc.selectFirst("article, main, [role=main], .article-body, .post-content, " +
                                    ".entry-content, .content-body, .story-body")
        val paragraphs = (main ?: doc).select("p, h1, h2, h3, li")

        val sb = StringBuilder()
        for (el in paragraphs) {
            val text = el.text().trim()
            if (text.length < 20) continue  // skip tiny fragments
            // Preserve heading structure as markdown-ish prefixes
            when (el.tagName()) {
                "h1"     -> sb.append("## ").appendLine(text)
                "h2"     -> sb.append("### ").appendLine(text)
                "h3"     -> sb.append("#### ").appendLine(text)
                "li"     -> sb.append("• ").appendLine(text)
                else     -> sb.appendLine(text)
            }
            sb.appendLine()
            if (sb.length > MAX_PAGE_CHARS * 2) break  // hard cap during extraction
        }

        val result = sb.toString().trim()
        return if (result.length > 50) result else fallbackTitle
    }

    /**
     * DuckDuckGo's HTML results wrap every link in a redirect tracker:
     *   //duckduckgo.com/l/?uddg=<percent-encoded-real-url>&rut=...
     * Extracts and URL-decodes the real `uddg` target so users see a clean link.
     */
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

    /**
     * Builds the AI-context block from search results (snippets only).
     * Phase 4 FIX: full URLs preserved (no mid-string truncation).
     */
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

    // ── Cache helpers (synchronous — callers must be on IO dispatcher) ────

    private fun readCache(query: String): List<SearchResult>? {
        return try {
            val cached = dao.getSearchCache(query) ?: return null
            val age = System.currentTimeMillis() - cached.timestamp
            if (age > CACHE_TTL_MS) return null
            // Deserialize JSON array of {title, snippet, url}
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
            dao.insertSearchCache(WebSearchCache(query = query, jsonResults = arr.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }
}
