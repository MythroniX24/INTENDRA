package com.interndra.search

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * BraveSearchProvider — Brave Search API (secondary provider).
 *
 * Use for: fast web lookup, official sites/docs, GitHub, independent
 * verification, search-result diversity. Requires a Brave API key stored in
 * Settings (never hardcoded).
 *
 * Docs: https://api.search.brave.com/app/documentation/web-search/get-started
 */
class BraveSearchProvider(
    apiKey: String
) : KeyedSearchProvider(apiKey) {

    override val id: SearchProviderId = SearchProviderId.BRAVE

    companion object {
        private const val TAG = "BraveSearch"
        const val BASE_URL = com.interndra.util.Constants.BRAVE_SEARCH_URL
    }

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = BASE_URL +
                "?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                "&count=$maxResults" +
                "&search_lang=en" +
                "&country=all" +
                "&source=web"

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("X-Subscription-Token", apiKey)
                .build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()
                if (!response.isSuccessful) {
                    val msg = parseError(body)
                    Log.w(TAG, "Brave HTTP ${response.code}: $msg")
                    throw IllegalStateException("Brave search failed: HTTP ${response.code} $msg")
                }

                val envelope = gson.fromJson(body, JsonObject::class.java)
                val web = envelope.getAsJsonObject("web") ?: return@withContext emptyList()
                val results = web.getAsJsonArray("results") ?: return@withContext emptyList()

                val out = mutableListOf<SearchResult>()
                for (i in 0 until results.size()) {
                    if (out.size >= maxResults) break
                    val r = results.get(i).asJsonObject
                    val title = r.get("title")?.asString?.trim() ?: ""
                    val url = r.get("url")?.asString?.trim() ?: ""
                    val description = r.get("description")?.asString?.trim() ?: ""
                    if (title.isNotBlank() && url.isNotBlank()) {
                        val age = r.get("age")?.asString
                        val pageAge = r.get("page_age")?.asString
                        out.add(
                            SearchResult(
                                title = title,
                                url = url,
                                snippet = description.take(300),
                                provider = SearchProviderId.BRAVE,
                                publishedMs = parseAgeToEpochMs(age ?: pageAge)
                            )
                        )
                    }
                }
                out
            } catch (e: Exception) {
                Log.e(TAG, "Brave search error: ${e.message}")
                throw e
            }
        }

    private fun parseError(body: String): String {
        return try {
            val obj = gson.fromJson(body, JsonObject::class.java)
            obj.get("message")?.asString ?: obj.toString().take(120)
        } catch (_: Exception) {
            body.take(120)
        }
    }

    /** Best-effort: parse Brave's "age" (e.g. "2h", "3d", "1w") into an epoch. */
    private fun parseAgeToEpochMs(age: String?): Long? {
        if (age.isNullOrBlank()) return null
        return try {
            val value = Regex("(\\d+)").find(age)?.groupValues?.getOrNull(1)?.toLong() ?: return null
            val now = System.currentTimeMillis()
            when {
                age.contains("min") -> now - value * 60_000L
                age.contains('h') -> now - value * 3_600_000L
                age.contains('d') -> now - value * 86_400_000L
                age.contains('w') -> now - value * 7 * 86_400_000L
                age.contains('m') -> now - value * 30L * 86_400_000L
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
