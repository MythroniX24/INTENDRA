package com.interndra.search

import java.net.URI

/**
 * ResultRanker — dedupes and ranks search results by source quality.
 *
 * Prefers official documentation, official websites, GitHub, research papers,
 * developer docs and trusted news. Avoids spam, SEO farms, clickbait and
 * low-quality aggregators.
 */
object ResultRanker {

    private val OFFICIAL_DOCS = listOf(
        "developer.android.com", "developers.google.com", "docs.python.org",
        "developer.mozilla.org", "react.dev", "nextjs.org", "kotlinlang.org",
        "docs.oracle.com", "learn.microsoft.com", "docs.docker.com",
        "kubernetes.io", "nodejs.org", "npmjs.com", "pip.pypa.io",
        "developer.apple.com", "docs.github.com", "cloud.google.com",
        "aws.amazon.com", "docs.aws.amazon.com", "flutter.dev",
        "developer.mozilla", "platform.openai.com", "docs.anthropic.com",
        "docs.mistral.ai", "ai.google.dev", "huggingface.co",
        "pypi.org", "crates.io", "maven.apache.org", "repo1.maven.org"
    )

    private val OFFICIAL_SITES = listOf(
        "github.com", "gitlab.com", "wikipedia.org", "gov.uk", "usa.gov",
        ".gov", ".edu", "who.int", "un.org", "europa.eu", "bbc.com",
        "reuters.com", "apnews.com", "theguardian.com", "nytimes.com",
        "wired.com", "arstechnica.com", "techcrunch.com", "verge.com",
        "stackoverflow.com", "stackexchange.com", "medium.com",
        "news.ycombinator.com", "arxiv.org", "scholar.google.com",
        "pubmed.ncbi.nlm.nih.gov", "nature.com", "science.org", "ieee.org",
        "acm.org", "springer.com", "elsevier.com", "w3.org", "ietf.org",
        "web.dev", "chrome.com", "android.com", "apple.com", "google.com"
    )

    private val LOW_QUALITY = listOf(
        "pinterest", "instagram", "facebook.com", "tiktok", "onlyfans",
        "4chan", "9gag", "buzzfeed", "clickbait", "thesun.co.uk",
        "dailymail.co.uk", "mirror.co.uk", "quora.com", "answers.com",
        "yahoo.com/answers", "tripadvisor", "yelp", "aliexpress",
        "amazon.com", "amazon.in", "flipkart", "ebay", "walmart",
        "temu", "shein", "wish.com", "ads", "sponsored", "taboola",
        "outbrain", "pornhub", "xnxx", "xvideos"
    )

    /**
     * Rank + dedupe a merged result list.
     *
     * @param results raw results from one or more providers.
     * @param maxResults how many to keep.
     * @return ranked results, deduped by normalized URL, best first.
     */
    fun rank(results: List<SearchResult>, maxResults: Int = 6): List<SearchResult> {
        if (results.isEmpty()) return emptyList()

        val deduped = LinkedHashMap<String, SearchResult>()
        for (r in results) {
            val key = normalizeUrl(r.url)
            if (key.isBlank()) continue
            val existing = deduped[key]
            if (existing == null || score(r) > score(existing)) {
                deduped[key] = r
            }
        }

        return deduped.values
            .sortedWith(compareByDescending<SearchResult> { score(it) }
                .thenByDescending { it.snippet.length })
            .take(maxResults)
    }

    /** Quality score for a single result (higher = better). */
    fun score(result: SearchResult): Int {
        var s = result.qualityScore
        val host = hostOf(result.url) ?: ""

        when {
            OFFICIAL_DOCS.any { host == it || host.endsWith(".$it") } -> s += 40
            OFFICIAL_SITES.any { host == it || host.endsWith(".$it") } -> s += 25
            LOW_QUALITY.any { host.contains(it) } -> s -= 30
        }

        // Snippet length = informativeness heuristic
        if (result.snippet.length >= 120) s += 5
        else if (result.snippet.length >= 50) s += 2

        // Title quality — penalize clickbait patterns
        val title = result.title.lowercase()
        if (title.contains("you won't believe") || title.contains("click here") ||
            title.contains("shocking") || title.contains("top 10 secrets")
        ) s -= 10

        return s
    }

    /** Freshness bonus: prefer results published within the last N days. */
    fun applyFreshnessBonus(results: List<SearchResult>, maxAgeDays: Long = 30): List<SearchResult> {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        return results.map { r ->
            val bonus = if (r.publishedMs != null && r.publishedMs >= cutoff) 15 else 0
            if (bonus == 0) r else r.copy(qualityScore = r.qualityScore + bonus)
        }
    }

    private fun normalizeUrl(url: String): String {
        return try {
            val u = URI(url)
            val scheme = u.scheme ?: return ""
            val host = u.host?.lowercase() ?: return ""
            // Strip trailing slash and fragment for dedupe
            val path = u.path?.trimEnd('/') ?: ""
            "$scheme://$host$path"
        } catch (_: Exception) {
            url.lowercase().trim()
        }
    }

    private fun hostOf(url: String): String? {
        return try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
