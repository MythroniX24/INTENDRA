package com.interndra.search

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction SearchManager depends on to resolve providers + settings.
 * [ProviderManager] implements this; unit tests can inject a fake.
 */
interface SearchProviderResolver {
    fun hasGeminiConfigured(): Boolean
    fun buildChain(plan: SearchPlan, settings: WebSearchSettings): List<SearchProvider>
}

/**
 * SearchManager — the autonomous web-search orchestrator.
 *
 * Pipeline (fully automatic, no user interaction):
 *   1. plan()      → decide whether/how to search
 *   2. search()    → run providers (parallel), collect + merge results
 *   3. rank()      → dedupe + rank by source quality
 *   4. readPages() → fetch full-page content when the plan requires it
 *   5. digest()    → build the final context injected into the AI prompt
 *
 * Every step reports [SearchStatus] via [onStatus] so the chat can show
 * subtle progress ("Searching the web…", "Reading webpages…").
 *
 * Caching: results cached per query (TTL 30 min); page digests cached per URL
 * (TTL 12 h). Concurrent identical searches share one in-flight Deferred, so
 * duplicate requests are never made twice.
 */
class SearchManager(
    private val planner: SearchPlanner,
    private val cache: SearchCache?,
    private val history: SearchHistory,
    private val webpageReader: WebpageReader,
    private val providerManager: SearchProviderResolver
) {

    companion object {
        private const val TAG = "SearchManager"
        private const val MAX_TOTAL_DIGEST = 6_000
    }

    /** Scope for starting in-flight dedup jobs without blocking the caller. */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight dedupe map to prevent duplicate concurrent searches. */
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<SearchDigest>>()

    /** Entry point: decide + run + digest in one call. */
    suspend fun runAutonomous(
        input: String,
        settings: WebSearchSettings,
        onStatus: (SearchStatus) -> Unit
    ): SearchDigest {
        val plan = planner.plan(
            input = input,
            searchEnabled = settings.searchEnabled,
            braveEnabled = settings.braveEnabled,
            braveKeyConfigured = settings.braveConfigured,
            geminiKeyConfigured = providerManager.hasGeminiConfigured(),
            preferBrave = settings.preferBrave
        )

        if (!plan.shouldSearch) {
            return SearchDigest.empty()
        }

        val primaryQuery = plan.queries.firstOrNull() ?: input
        val cacheKey = primaryQuery.trim().lowercase()

        // ── Cache / short-circuit ─────────────────────────────────────────
        if (cache != null) {
            val cached = cache.getResults(primaryQuery)
            if (!cached.isNullOrEmpty()) {
                Log.d(TAG, "Cache hit for '$cacheKey'")
                onStatus(SearchStatus.Found(cached.size, cached.first().provider))
                onStatus(SearchStatus.Done(cached.first().provider))
                history.record(SearchHistory.Entry(
                    query = primaryQuery,
                    providers = cached.map { it.provider }.distinct(),
                    resultCount = cached.size,
                    cacheHit = true
                ))
                return SearchDigest(
                    results = cached,
                    context = CitationManager.buildContextDigest(cached),
                    pageDigests = "",
                    providersUsed = cached.map { it.provider }.distinct(),
                    cacheHit = true
                )
            }
        }

        // ── Dedupe concurrent identical searches ──────────────────────────
        inFlight[cacheKey]?.let { return it.await() }

        val deferred = searchScope.async(Dispatchers.IO) {
            performSearch(plan, primaryQuery, settings, onStatus)
        }
        inFlight[cacheKey] = deferred
        return try {
            deferred.await()
        } finally {
            inFlight.remove(cacheKey, deferred)
        }
    }

    private suspend fun performSearch(
        plan: SearchPlan,
        primaryQuery: String,
        settings: WebSearchSettings,
        onStatus: (SearchStatus) -> Unit
    ): SearchDigest = withContext(Dispatchers.IO) {
        val chain = providerManager.buildChain(plan, settings)

        onStatus(SearchStatus.Searching(chain.first().id))

        // ── Run providers in parallel (bounded) ───────────────────────────
        val rawResults = runProviders(chain, primaryQuery, plan.maxResults * 3, onStatus)

        if (rawResults.isEmpty()) {
            onStatus(SearchStatus.Failed("no results from any provider"))
            return@withContext SearchDigest.empty()
        }

        // ── Rank + freshness bonus ────────────────────────────────────────
        val scored = if (plan.freshnessCritical) {
            ResultRanker.applyFreshnessBonus(rawResults)
        } else rawResults
        val ranked = ResultRanker.rank(scored, plan.maxResults)

        onStatus(SearchStatus.Found(ranked.size, ranked.first().provider))

        // ── Optional page reading ─────────────────────────────────────────
        var pageDigests = ""
        if (plan.readPages && ranked.isNotEmpty()) {
            onStatus(SearchStatus.Reading(minOf(2, ranked.size)))
            pageDigests = readPagesWithCache(ranked)
            if (pageDigests.isNotBlank()) {
                onStatus(SearchStatus.Analyzing("synthesizing page content"))
            }
        }

        // ── Persist cache ────────────────────────────────────────────────
        cache?.putResults(primaryQuery, ranked)

        history.record(SearchHistory.Entry(
            query = primaryQuery,
            providers = ranked.map { it.provider }.distinct(),
            resultCount = ranked.size,
            cacheHit = false
        ))

        val providersUsed = ranked.map { it.provider }.distinct()
        onStatus(SearchStatus.Done(providersUsed.first()))

        SearchDigest(
            results = ranked,
            context = CitationManager.buildContextDigest(ranked),
            pageDigests = pageDigests,
            providersUsed = providersUsed,
            cacheHit = false
        )
    }

    private suspend fun runProviders(
        chain: List<SearchProvider>,
        query: String,
        maxResults: Int,
        onStatus: (SearchStatus) -> Unit
    ): List<SearchResult> = coroutineScope {
        val results = chain.map { provider ->
            async(Dispatchers.IO) {
                try {
                    if (!provider.isAvailable()) return@async emptyList()
                    onStatus(SearchStatus.Searching(provider.id))
                    provider.search(query, maxResults)
                } catch (e: Exception) {
                    Log.w(TAG, "Provider ${provider.id} failed: ${e.message}")
                    onStatus(SearchStatus.Analyzing("falling back: ${provider.id} unavailable"))
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        results
    }

    private suspend fun readPagesWithCache(results: List<SearchResult>): String {
        return withContext(Dispatchers.IO) {
            // Per-URL cache: fetch only misses, cache each page's own content.
            val sb = StringBuilder()
            sb.appendLine("\n[Web Page Content — fetched and extracted]")
            var totalChars = 0
            val targets = results.take(2)
            var idx = 0
            for (r in targets) {
                val cached = cache?.getPageDigest(r.url)
                val content = if (!cached.isNullOrBlank()) {
                    cached
                } else {
                    val fetched = webpageReader.readPageContent(r)
                    if (fetched.isNotBlank()) cache?.putPageDigest(r.url, fetched)
                    fetched
                }
                if (content.isBlank()) continue
                idx++
                val entry = buildString {
                    appendLine("Source [$idx]: ${r.title.ifBlank { "Untitled" }}")
                    appendLine("URL: ${r.url}")
                    appendLine("Content:")
                    appendLine(content)
                    appendLine()
                }
                if (totalChars + entry.length > MAX_TOTAL_DIGEST) {
                    sb.appendLine("…(additional sources truncated to fit context)")
                    break
                }
                sb.append(entry)
                totalChars += entry.length
            }
            sb.toString().trim().ifEmpty { "" }
        }
    }
}
