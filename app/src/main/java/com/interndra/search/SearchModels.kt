package com.interndra.search

/**
 * SearchModels — core types shared across the autonomous web-search system.
 *
 * The whole pipeline is provider-agnostic: providers return [SearchResult]s,
 * the [SearchPlanner] produces a [SearchPlan], and the [SearchManager]
 * orchestrates everything into a [SearchDigest] that gets injected into the
 * AI prompt.
 */

/** Which provider produced a result. Future providers (Tavily, Exa, SearXNG…) just add an enum entry. */
enum class SearchProviderId(val label: String) {
    GEMINI("Gemini Search"),
    BRAVE("Brave Search"),
    DUCKDUCKGO("DuckDuckGo"),
    UNKNOWN("Search")
}

/** A single web-search hit, normalized across all providers. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    val provider: SearchProviderId = SearchProviderId.UNKNOWN,
    /** Timestamp (epoch ms) if the source reports its age — used for freshness ranking. */
    val publishedMs: Long? = null,
    /** Internal trust/quality score assigned by [ResultRanker]; higher is better. */
    val qualityScore: Int = 0
)

/** The planner's decision for a single user message. */
data class SearchPlan(
    val shouldSearch: Boolean,
    /** 0..1 — how confident the planner is that a search is warranted. */
    val confidence: Float = 0f,
    /** Human-readable reason, used for debug logs and status hints. */
    val reason: String = "",
    /** Optimized query set. First entry is the primary query. */
    val queries: List<String> = emptyList(),
    /** Whether to fetch+extract full page content (vs. snippets only). */
    val readPages: Boolean = false,
    /** Preferred provider order, based on settings + query type. */
    val preferredProviders: List<SearchProviderId> = emptyList(),
    /** Number of results to keep after ranking. */
    val maxResults: Int = 6,
    /** Whether this is a freshness-critical query (news, prices, releases). */
    val freshnessCritical: Boolean = false
) {
    companion object {
        /** Convenience: a plan that says "don't search". */
        fun none(reason: String = "") = SearchPlan(
            shouldSearch = false,
            reason = reason
        )
    }
}

/** Status updates the manager reports to the UI while working. */
sealed class SearchStatus {
    data class Searching(val provider: SearchProviderId) : SearchStatus()
    data class Found(val count: Int, val provider: SearchProviderId) : SearchStatus()
    data class Reading(val count: Int) : SearchStatus()
    data class Analyzing(val stage: String) : SearchStatus()
    data class Done(val providerUsed: SearchProviderId) : SearchStatus()
    data class Failed(val message: String) : SearchStatus()
}

/** Final output of a search run, ready to be appended to the AI prompt. */
data class SearchDigest(
    val results: List<SearchResult>,
    /** Ranked + deduped results, formatted as markdown context for the AI. */
    val context: String,
    /** Full-text digests of read web pages (empty when pages weren't read). */
    val pageDigests: String,
    /** Which provider(s) actually produced the results. */
    val providersUsed: List<SearchProviderId> = emptyList(),
    val cacheHit: Boolean = false
) {
    companion object {
        fun empty() = SearchDigest(
            results = emptyList(),
            context = "",
            pageDigests = ""
        )
    }
}
