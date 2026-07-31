package com.interndra.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WebpageReader — fetches a page and extracts its main readable content.
 *
 * Used when the planner decides full-page reading is required (research
 * questions, docs, deep-dives). Results are truncated to a bounded digest so
 * the AI prompt stays small.
 */
class WebpageReader(
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "WebpageReader"
        private const val MAX_PAGE_CHARS = 2_500

        private val SKIP_FETCH_DOMAINS = setOf(
            "youtube.com", "youtu.be", "facebook.com", "instagram.com",
            "twitter.com", "x.com", "tiktok.com", "reddit.com",
            "pinterest.com", "amazon.com", "amazon.in", "linkedin.com"
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Extract the readable content of a single page. Returns just the body
     * text (no Source/URL wrapper) so callers can cache it per-URL.
     */
    suspend fun readPageContent(result: SearchResult): String =
        withContext(Dispatchers.IO) {
            if (result.url.isBlank() || !shouldFetch(result.url)) return@withContext ""
            fetchPageText(result)?.take(MAX_PAGE_CHARS) ?: ""
        }

    private fun shouldFetch(url: String): Boolean {
        if (url.isBlank()) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
            ?: return false
        return SKIP_FETCH_DOMAINS.none { host == it || host.endsWith(".$it") }
    }

    /** Fetch + extract the main text of a page; null on failure or skip. */
    private fun fetchPageText(result: SearchResult): String? {
        return try {
            val request = Request.Builder()
                .url(result.url)
                .header("User-Agent", "Mozilla/5.0 (compatible; INTENDRA-ResearchBot/1.0)")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Page fetch failed (${response.code}): ${result.url.take(60)}")
                return null
            }
            val html = response.body?.string() ?: return null
            ContentExtractor.extractMainContent(html, result.title)
        } catch (e: Exception) {
            Log.w(TAG, "Page fetch error for ${result.url.take(60)}: ${e.message}")
            null
        }
    }
}
