package com.interndra.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.interndra.data.model.AiEngineResult
import com.interndra.data.model.AiSource
import com.interndra.data.model.CommandMemory
import com.interndra.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * CloudAiEngine — OpenRouter API for complex reasoning tasks.
 *
 * UPGRADE over original:
 * - Reads token usage and logs it at DEBUG level only (no production leakage)
 * - Redacts API key from any error messages before propagation
 * - Adds X-Request-ID header for tracing without sending PII
 */
class CloudAiEngine(
    private val apiKey: String,
    private val model: String = Constants.DEFAULT_MODEL
) {
    private val TAG  = "CloudAiEngine"
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .callTimeout(Constants.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured() = apiKey.isNotBlank()

    suspend fun parseIntent(
        userInput: String,
        memory: List<CommandMemory>,
        chatHistory: List<Pair<String, String>> = emptyList(),
        jailbreakActive: Boolean = false,
        jailbreakLevel: JailbreakLevel = JailbreakLevel.OFF
    ): AiEngineResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        // Inject jailbreak into system prompt if active
        val systemPrompt = if (jailbreakActive && jailbreakLevel != JailbreakLevel.OFF) {
            JailbreakEngine.injectJailbreak(Constants.AI_SYSTEM_PROMPT, jailbreakLevel)
        } else {
            Constants.AI_SYSTEM_PROMPT
        }

        val obfuscatedInput = if (jailbreakActive && jailbreakLevel != JailbreakLevel.OFF) {
            JailbreakEngine.obfuscateInput(userInput, jailbreakLevel)
        } else {
            userInput
        }

        val messages = buildList {
            add(mapOf("role" to "system", "content" to systemPrompt))
            // Real conversation history — gives AI memory of what was said
            chatHistory.takeLast(16).forEach { (role, content) ->
                add(mapOf("role" to role, "content" to content))
            }
            // Fallback: command memory if no chat history
            if (chatHistory.isEmpty()) {
                memory.takeLast(6).forEach { m ->
                    add(mapOf("role" to "user", "content" to m.userInput))
                    add(mapOf("role" to "assistant", "content" to "Action: ${m.actionType}"))
                }
            }
            add(mapOf("role" to "user", "content" to obfuscatedInput))
        }

        val bodyJson = gson.toJson(mapOf(
            "model"       to model,
            "max_tokens"  to 1024,
            "temperature" to 0.1,
            "messages"    to messages
        ))

        val requestId = java.util.UUID.randomUUID().toString().take(8)

        val request = Request.Builder()
            .url(Constants.OPENROUTER_BASE_URL)
            .addHeader("Authorization",  "Bearer $apiKey")
            .addHeader("Content-Type",   "application/json")
            .addHeader("HTTP-Referer",   "https://github.com/INTERNDRA")
            .addHeader("X-Title",        "INTERNDRA AI OS")
            .addHeader("X-Request-ID",   requestId)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body     = response.body?.string()
                ?: throw IllegalStateException("Empty response from OpenRouter [$requestId]")

            if (!response.isSuccessful) {
                // SECURITY: never log raw body (may contain partial API key echo or user data)
                throw IllegalStateException("OpenRouter HTTP ${response.code} [$requestId]")
            }

            val envelope = gson.fromJson(body, JsonObject::class.java)
            if (envelope.has("error")) {
                val msg = envelope.getAsJsonObject("error").get("message")?.asString ?: "API error"
                throw IllegalStateException("OpenRouter: $msg [$requestId]")
            }

            val content = envelope
                .getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()

            val tokens = envelope.getAsJsonObject("usage")
                ?.get("total_tokens")?.asInt ?: 0

            val latency = System.currentTimeMillis() - startMs
            Log.d(TAG, "Cloud done — ${latency}ms, $tokens tokens, model=$model [$requestId]")

            AiEngineResult(
                intentJson = cleanJson(content),
                source     = AiSource.CLOUD,
                modelUsed  = model,
                latencyMs  = latency,
                tokenCount = tokens
            )
        } catch (e: Exception) {
            // SECURITY: remove any accidental API-key leakage from exception messages
            val safeMsg = e.message?.replace(apiKey, "[REDACTED]") ?: "Unknown error"
            Log.e(TAG, "Cloud error: $safeMsg")
            throw IllegalStateException(safeMsg)
        }
    }

    /**
     * Robust JSON extraction — handles responses where the model wraps the
     * JSON object with explanation text, markdown fences, or both
     * (e.g. "Sure! Here's the JSON:\n```json\n{...}\n```\nLet me know!").
     * Finds the OUTERMOST {...} block regardless of surrounding text.
     */
    /**
     * Robust JSON extraction with action-key awareness.
     * Finds the JSON object that contains "action" key — not just the first { in the string.
     * Fixes: model wrapping JSON in text with curly braces (city names, emojis with {}).
     */
    /**
     * Extracts ONE balanced {...} object starting at [start], properly
     * tracking string literals (so braces inside quoted text don't confuse
     * depth counting) and stopping at the EXACT matching close brace.
     *
     * This replaces the old "lastIndexOf('}')" approach, which grabbed the
     * LAST closing brace in the whole raw string — if the model appended any
     * trailing garbage after the real JSON (duplicated fragments, stray
     * `,"steps":[],"commands":[]}`), that garbage got pulled into the result
     * and leaked into the chat. Proper depth-matching ignores everything
     * after the real object ends.
     */
    private fun extractBalancedJson(s: String, start: Int): String? {
        if (start < 0 || start >= s.length || s[start] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            when {
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null // unbalanced — no matching close found
    }

    private fun cleanJson(raw: String): String {
        val stripped = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val quote = '"'
        val actionKey = "${quote}action${quote}"

        // Strategy 1: Find the { that's immediately before the "action" key,
        // then extract exactly the balanced object from there.
        val actionIdx = stripped.indexOf(actionKey)
        if (actionIdx > 0) {
            val braceStart = stripped.lastIndexOf('{', actionIdx)
            if (braceStart >= 0) {
                extractBalancedJson(stripped, braceStart)?.let { return it }
            }
        }

        // Strategy 2: First { with proper balanced matching (not lastIndexOf('}'))
        val start = stripped.indexOf('{')
        if (start >= 0) {
            extractBalancedJson(stripped, start)?.let { return it }
        }

        // Strategy 3: No valid JSON at all — wrap the plain text as a chat reply
        if (stripped.isBlank()) {
            return "{${quote}action${quote}:${quote}unknown${quote},${quote}commands${quote}:[]}"
        }
        val safeText = stripped.take(500)
            .replace("\\", "/")
            .replace(quote.toString(), "'")
        return "{${quote}action${quote}:${quote}chat${quote},${quote}reply${quote}:${quote}$safeText${quote},${quote}steps${quote}:[],${quote}commands${quote}:[]}"
    }
}
