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
 * GeminiAiEngine — Google Gemini API integration for AI inference.
 *
 * Converts the standard chat format to Gemini's message format,
 * handles both generateContent (non-streaming) calls,
 * and supports jailbreak prompt injection.
 */
class GeminiAiEngine(
    private val apiKey: String,
    private val model: String = Constants.DEFAULT_GEMINI_MODEL
) {
    private val TAG = "GeminiAiEngine"
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

        val systemPrompt = if (jailbreakActive && jailbreakLevel != JailbreakLevel.OFF) {
            JailbreakEngine.injectJailbreak(Constants.AI_SYSTEM_PROMPT, jailbreakLevel)
        } else {
            Constants.AI_SYSTEM_PROMPT
        }

        // Build Gemini contents array
        val contents = mutableListOf<Map<String, Any>>().apply {
            // System instruction is passed separately in Gemini API
            // Chat history
            chatHistory.takeLast(16).forEach { (role, content) ->
                val geminiRole = when (role.lowercase()) {
                    "assistant" -> "model"
                    else -> "user"
                }
                add(mapOf("role" to geminiRole, "parts" to listOf(mapOf("text" to content))))
            }
            // Command memory fallback if no chat history
            if (chatHistory.isEmpty()) {
                memory.takeLast(6).forEach { m ->
                    add(mapOf("role" to "user", "parts" to listOf(mapOf("text" to m.userInput))))
                    add(mapOf("role" to "model", "parts" to listOf(mapOf("text" to "Action: ${m.actionType}"))))
                }
            }
            // Current user input
            val obfuscatedInput = if (jailbreakActive && jailbreakLevel != JailbreakLevel.OFF) {
                JailbreakEngine.obfuscateInput(userInput, jailbreakLevel)
            } else {
                userInput
            }
            add(mapOf("role" to "user", "parts" to listOf(mapOf("text" to obfuscatedInput))))
        }

        val requestBody = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemPrompt))
            ),
            "contents" to contents,
            "generationConfig" to mapOf(
                "maxOutputTokens" to 1024,
                "temperature" to 0.7,
                "topP" to 0.9
            )
        )

        val bodyJson = gson.toJson(requestBody)
        val modelName = stripModelPrefix(model)
        val url = "${Constants.GEMINI_BASE_URL}/models/$modelName:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty response from Gemini")

            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini HTTP ${response.code}: ${body.take(200)}")
            }

            val envelope = gson.fromJson(body, JsonObject::class.java)

            // Check for error in response
            if (envelope.has("error")) {
                val err = envelope.getAsJsonObject("error")
                val msg = err.get("message")?.asString ?: "API error"
                throw IllegalStateException("Gemini API error: $msg")
            }

            // Check for blocked content
            if (envelope.has("promptFeedback")) {
                val feedback = envelope.getAsJsonObject("promptFeedback")
                if (feedback.has("blockReason")) {
                    val reason = feedback.get("blockReason").asString
                    Log.w(TAG, "Content blocked: $reason")
                    // Return a response indicating the block
                    return@withContext AiEngineResult(
                        intentJson = """{"action":"chat","reply":"⚠️ Content was blocked by Gemini safety filters (reason: $reason). Try using Jailbreak mode in Settings to bypass this.","commands":[]}""",
                        source = AiSource.CLOUD,
                        modelUsed = model,
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                }
            }

            val candidate = envelope
                .getAsJsonArray("candidates")
                ?.get(0)?.asJsonObject
                ?: throw IllegalStateException("No candidates in Gemini response")

            // Check finish reason
            if (candidate.has("finishReason")) {
                val finishReason = candidate.get("finishReason").asString
                if (finishReason == "SAFETY" || finishReason == "BLOCKLIST") {
                    Log.w(TAG, "Response blocked by safety: $finishReason")
                    return@withContext AiEngineResult(
                        intentJson = """{"action":"chat","reply":"⚠️ Response was blocked by Gemini safety filters (finish reason: $finishReason). Try rephrasing or enabling Jailbreak mode.","commands":[]}""",
                        source = AiSource.CLOUD,
                        modelUsed = model,
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                }
            }

            val content = candidate
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).asJsonObject
                .get("text").asString
                .trim()

            // Get token usage if available
            val usageMetadata = envelope.getAsJsonObject("usageMetadata")
            val promptTokens = usageMetadata?.get("promptTokenCount")?.asInt ?: 0
            val outputTokens = usageMetadata?.get("candidatesTokenCount")?.asInt ?: 0

            val latency = System.currentTimeMillis() - startMs
            Log.d(TAG, "Gemini done — ${latency}ms, $promptTokens+$outputTokens tokens, model=$modelName")

            AiEngineResult(
                intentJson = cleanJson(content),
                source = AiSource.CLOUD,
                modelUsed = model,
                latencyMs = latency,
                tokenCount = promptTokens + outputTokens
            )
        } catch (e: Exception) {
            val safeMsg = e.message?.replace(apiKey, "[REDACTED]") ?: "Unknown error"
            Log.e(TAG, "Gemini error: $safeMsg")
            throw IllegalStateException(safeMsg)
        }
    }

    /**
     * Strip the "gemini/" prefix if present (from model display names)
     */
    private fun stripModelPrefix(modelStr: String): String {
        return modelStr.removePrefix("gemini/").trim()
    }

    /**
     * Robust JSON extraction from model response
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
        return null
    }

    private fun cleanJson(raw: String): String {
        val stripped = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val quote = '"'
        val actionKey = "${quote}action${quote}"

        val actionIdx = stripped.indexOf(actionKey)
        if (actionIdx > 0) {
            val braceStart = stripped.lastIndexOf('{', actionIdx)
            if (braceStart >= 0) {
                extractBalancedJson(stripped, braceStart)?.let { return it }
            }
        }

        val start = stripped.indexOf('{')
        if (start >= 0) {
            extractBalancedJson(stripped, start)?.let { return it }
        }

        if (stripped.isBlank()) {
            return "{${quote}action${quote}:${quote}unknown${quote},${quote}commands${quote}:[]}"
        }
        val safeText = stripped.take(500)
            .replace("\\", "/")
            .replace(quote.toString(), "'")
        return "{${quote}action${quote}:${quote}chat${quote},${quote}reply${quote}:${quote}$safeText${quote},${quote}steps${quote}:[],${quote}commands${quote}:[]}"
    }

    suspend fun validateApiKey(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${Constants.GEMINI_BASE_URL}/models/${stripModelPrefix(model)}:generateContent?key=$apiKey"
            val bodyJson = """{
                "contents": [{"parts": [{"text": "Say 'ok' and nothing else"}]}],
                "generationConfig": {"maxOutputTokens": 5}
            }"""
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Key validation failed: ${e.message}")
            false
        }
    }
}
