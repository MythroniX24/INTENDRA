package com.interndra.search

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * GeminiSearchProvider — Gemini Google Search grounding tool.
 *
 * Uses Gemini's native `googleSearch` tool (grounding with Google Search),
 * which returns real Google-indexed results with titles + URLs. This is the
 * PRIMARY provider: latest info, research, docs, news — everything Google
 * indexes.
 *
 * The model must support the googleSearch tool (gemini-2.5-flash and newer).
 * We parse `groundingMetadata.groundingChunks` from the response.
 */
class GeminiSearchProvider(
    apiKey: String,
    private val model: String = "gemini-2.5-flash"
) : KeyedSearchProvider(apiKey) {

    override val id: SearchProviderId = SearchProviderId.GEMINI

    companion object {
        private const val TAG = "GeminiSearch"
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    }

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val cleanModel = model.trim().removePrefix("gemini/")
            val url = "$GEMINI_BASE/models/$cleanModel:generateContent?key=$apiKey"

            val requestBody = JsonObject().apply {
                // Ask the model to do the search itself (minimal tokens — we only
                // need the grounding metadata, not the narration).
                add("contents", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("text", "Search the web for: $query")
                            })
                        })
                    })
                })
                // The magic: googleSearch tool enables Google-grounded results.
                add("tools", JsonArray().apply {
                    add(JsonObject().apply {
                        add("googleSearch", JsonObject())
                    })
                })
                add("generationConfig", JsonObject().apply {
                    addProperty("maxOutputTokens", 200)
                    addProperty("temperature", 0.0)
                })
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Gemini search HTTP ${response.code}: ${body.take(200)}")
                    throw IllegalStateException("Gemini search failed: HTTP ${response.code}")
                }

                val envelope = gson.fromJson(body, JsonObject::class.java)
                val candidate = envelope.getAsJsonArray("candidates")?.get(0)?.asJsonObject
                    ?: return@withContext emptyList()

                val grounding = candidate.getAsJsonObject("groundingMetadata")
                    ?: return@withContext emptyList()

                val chunks = grounding.getAsJsonArray("groundingChunks") ?: return@withContext emptyList()
                val supports = grounding.getAsJsonArray("groundingSupports") ?: JsonArray()

                val results = mutableListOf<SearchResult>()
                var idx = 0
                while (idx < chunks.size() && results.size < maxResults) {
                    val chunk = chunks.get(idx).asJsonObject
                    val web = chunk.getAsJsonObject("web")
                    if (web != null) {
                        val title = web.get("title")?.asString?.trim() ?: ""
                        val uri = web.get("uri")?.asString?.trim() ?: ""
                        if (title.isNotBlank() && uri.isNotBlank()) {
                            val snippet = extractSnippetForChunk(supports, idx)
                            results.add(
                                SearchResult(
                                    title = title,
                                    url = uri,
                                    snippet = snippet,
                                    provider = SearchProviderId.GEMINI
                                )
                            )
                        }
                    }
                    idx++
                }
                results
            } catch (e: Exception) {
                Log.e(TAG, "Gemini search error: ${e.message}")
                throw e
            }
        }

    /**
     * groundingSupports maps text segments to chunk indices — we use it to
     * recover a snippet for each grounding chunk when available.
     */
    private fun extractSnippetForChunk(supports: JsonArray, chunkIndex: Int): String {
        for (i in 0 until supports.size()) {
            val support = supports.get(i).asJsonObject
            val indices = support.getAsJsonArray("groundingChunkIndices") ?: continue
            for (j in 0 until indices.size()) {
                if (indices.get(j).asInt == chunkIndex) {
                    val segment = support.getAsJsonObject("segment")
                    val text = segment?.get("text")?.asString?.trim()
                    if (!text.isNullOrBlank()) return text.take(300)
                }
            }
        }
        return ""
    }
}
