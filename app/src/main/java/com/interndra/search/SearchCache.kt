package com.interndra.search

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.interndra.data.local.AgentDao
import com.interndra.data.model.WebSearchCache

/**
 * SearchCache — persists search results and page digests in Room.
 *
 * - Results cached per (query) with a TTL.
 * - Page digests cached per (url) with a longer TTL.
 * - Stale entries are transparently refreshed on next access.
 */
class SearchCache(private val dao: AgentDao) {

    companion object {
        private const val TAG = "SearchCache"
        private const val RESULTS_TTL_MS = 30L * 60 * 1000   // 30 min
        private const val PAGE_TTL_MS = 12L * 60 * 60 * 1000 // 12 h
        private const val PAGE_PREFIX = "PAGE::"
    }

    private val gson = Gson()

    // ── Results ────────────────────────────────────────────────────────────

    suspend fun getResults(query: String): List<SearchResult>? {
        if (query.isBlank()) return null
        return try {
            val cached = dao.getSearchCache(query, System.currentTimeMillis() - RESULTS_TTL_MS)
                ?: return null
            parseResults(cached.jsonResults)
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed: ${e.message}")
            null
        }
    }

    suspend fun putResults(query: String, results: List<SearchResult>) {
        if (query.isBlank() || results.isEmpty()) return
        try {
            dao.insertSearchCache(
                WebSearchCache(query = query, jsonResults = toJson(results))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }

    // ── Page digests ───────────────────────────────────────────────────────

    suspend fun getPageDigest(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val cached = dao.getSearchCache(
                PAGE_PREFIX + url, System.currentTimeMillis() - PAGE_TTL_MS
            ) ?: return null
            cached.jsonResults
        } catch (e: Exception) {
            null
        }
    }

    suspend fun putPageDigest(url: String, digest: String) {
        if (url.isBlank() || digest.isBlank()) return
        try {
            dao.insertSearchCache(
                WebSearchCache(query = PAGE_PREFIX + url, jsonResults = digest)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Page cache write failed: ${e.message}")
        }
    }

    // ── Serialization ──────────────────────────────────────────────────────

    private fun toJson(results: List<SearchResult>): String {
        val arr = JsonArray()
        results.forEach { r ->
            val o = JsonObject().apply {
                addProperty("title", r.title)
                addProperty("url", r.url)
                addProperty("snippet", r.snippet)
                addProperty("provider", r.provider.name)
                r.publishedMs?.let { addProperty("publishedMs", it) }
                addProperty("qualityScore", r.qualityScore)
            }
            arr.add(o)
        }
        return arr.toString()
    }

    private fun parseResults(json: String): List<SearchResult>? {
        return try {
            val arr = gson.fromJson(json, JsonArray::class.java)
            arr.map { obj ->
                val o = obj.asJsonObject
                SearchResult(
                    title = o.get("title")?.asString ?: "",
                    url = o.get("url")?.asString ?: "",
                    snippet = o.get("snippet")?.asString ?: "",
                    provider = runCatching {
                        SearchProviderId.valueOf(o.get("provider")?.asString ?: "UNKNOWN")
                    }.getOrDefault(SearchProviderId.UNKNOWN),
                    publishedMs = o.get("publishedMs")?.asLong,
                    qualityScore = o.get("qualityScore")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache parse failed: ${e.message}")
            null
        }
    }
}
