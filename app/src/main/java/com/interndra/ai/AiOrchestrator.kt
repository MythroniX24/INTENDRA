package com.interndra.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.interndra.data.model.*
import com.interndra.util.Constants

/**
 * AiOrchestrator — routes requests between local, OpenRouter cloud, or Google Gemini AI.
 *
 * UPGRADE: Added Gemini provider support, jailbreak prompt injection, and
 * advanced provider routing.
 */
class AiOrchestrator(
    private val context: Context,
    private val localEngine: LocalAiEngine,
    private val cloudEngine: CloudAiEngine,
    private val geminiEngine: GeminiAiEngine? = null
) {
    private val TAG  = "AiOrchestrator"
    private val gson = Gson()

    // ── Provider selection ────────────────────────────────────────────────
    var activeProvider: Constants.AiProvider = Constants.AiProvider.OPENROUTER
    var jailbreakActive: Boolean = false
    var jailbreakLevel: JailbreakLevel = JailbreakLevel.OFF

    private val CLOUD_KEYWORDS = setOf(
        "analyze", "explain", "debug", "code", "script", "generate", "create workflow",
        "plan", "strategy", "complex", "zip file", "project", "repository", "understand",
        "summarize", "write", "draft", "review", "compare", "difference between",
        "how to", "what is", "why does", "help me build", "automation workflow",
        "regex", "algorithm", "function", "class", "implement"
    )

    private val LOCAL_KEYWORDS = setOf(
        "open", "launch", "start", "close", "call", "message", "send", "screenshot",
        "battery", "storage", "volume", "brightness", "wifi", "bluetooth", "home",
        "back", "list files", "ls", "unzip", "extract", "copy", "move", "delete",
        "git status", "git push", "git pull", "install", "hi", "hello", "hey"
    )

    /**
     * Process user input. [onCloudConsentNeeded] is called when cloud escalation
     * would be attempted in HYBRID mode — caller must resume or deny the suspend lambda.
     */
    suspend fun process(
        userInput: String,
        memory: List<CommandMemory>,
        privacyMode: PrivacyMode,
        onCloudConsentNeeded: suspend ((allow: Boolean) -> Unit) -> Unit = { it(true) },
        chatHistory: List<Pair<String, String>> = emptyList()
    ): OrchestratorResult {

        val routingDecision = decideRoute(userInput, privacyMode)
        Log.d(TAG, "Routing: $routingDecision for '${userInput.take(60)}'")

        val result: AiEngineResult = when (routingDecision) {
            RoutingDecision.LOCAL -> runLocal(userInput, memory)

            RoutingDecision.CLOUD -> runCloud(userInput, memory, chatHistory)

            RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK -> {
                val localResult = runLocal(userInput, memory)
                if (isGoodResult(localResult.intentJson)) {
                    localResult
                } else if (isInternetAvailable() && isCloudConfigured()) {
                    var userAllowed = false
                    onCloudConsentNeeded { allowed -> userAllowed = allowed }
                    if (userAllowed) {
                        try {
                            runCloud(userInput, memory, chatHistory).copy(source = AiSource.FALLBACK)
                        } catch (e: Exception) { localResult }
                    } else {
                        Log.d(TAG, "Cloud escalation denied by user — using local result")
                        localResult
                    }
                } else {
                    localResult
                }
            }
        }

        val intent = parseToAiIntent(result.intentJson)

        // Post-process: check if model refused (jailbreak was ineffective)
        if (jailbreakActive && jailbreakLevel != JailbreakLevel.OFF && routingDecision != RoutingDecision.LOCAL) {
            if (JailbreakEngine.isRefusal(result.intentJson)) {
                Log.w(TAG, "Jailbreak may have failed — model showed refusal patterns")
            }
        }

        return OrchestratorResult(
            intent          = intent,
            engineResult    = result,
            routingDecision = routingDecision,
            explanation     = buildExplanation(result)
        )
    }

    private fun isCloudConfigured(): Boolean {
        return when (activeProvider) {
            Constants.AiProvider.OPENROUTER -> cloudEngine.isConfigured()
            Constants.AiProvider.GEMINI -> geminiEngine?.isConfigured() ?: false
        }
    }

    private suspend fun runCloud(
        userInput: String,
        memory: List<CommandMemory>,
        chatHistory: List<Pair<String, String>> = emptyList()
    ): AiEngineResult {
        return when (activeProvider) {
            Constants.AiProvider.OPENROUTER -> {
                try {
                    cloudEngine.parseIntent(userInput, memory, chatHistory,
                        jailbreakActive = jailbreakActive,
                        jailbreakLevel = jailbreakLevel
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "OpenRouter error: ${e.message} — falling back to local")
                    runLocal(userInput, memory).copy(source = AiSource.FALLBACK)
                }
            }
            Constants.AiProvider.GEMINI -> {
                val engine = geminiEngine
                if (engine == null || !engine.isConfigured()) {
                    Log.w(TAG, "Gemini not configured, falling back to OpenRouter")
                    try {
                        cloudEngine.parseIntent(userInput, memory, chatHistory,
                            jailbreakActive = jailbreakActive,
                            jailbreakLevel = jailbreakLevel
                        )
                    } catch (e: Exception) {
                        runLocal(userInput, memory).copy(source = AiSource.FALLBACK)
                    }
                } else {
                    try {
                        engine.parseIntent(userInput, memory, chatHistory,
                            jailbreakActive = jailbreakActive,
                            jailbreakLevel = jailbreakLevel
                        )
                    } catch (geminiErr: Exception) {
                        val rawMsg = geminiErr.message ?: "Unknown Gemini error"
                        val geminiMsg = rawMsg
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .take(300)
                        Log.e(TAG, "Gemini error: $geminiMsg — falling back to OpenRouter")
                        // Try OpenRouter fallback, but if it also fails, return Gemini error
                        try {
                            cloudEngine.parseIntent(userInput, memory, chatHistory,
                                jailbreakActive = jailbreakActive,
                                jailbreakLevel = jailbreakLevel
                            ).copy(source = AiSource.FALLBACK)
                        } catch (e2: Exception) {
                            // Both Gemini AND OpenRouter failed — show Gemini error to user
                            runLocal(userInput, memory).copy(
                                source = AiSource.FALLBACK,
                                modelUsed = "gemini-error",
                                intentJson = """{"action":"chat","reply":"⚠️ **Gemini API Error:** $geminiMsg\n\n**Troubleshooting:**\n- Check your Gemini API key in Settings\n- Make sure you have internet\n- Try a different Gemini model in Settings\n- The selected model may be unavailable — try Gemini 3.5 Flash","commands":[]}"""
                            )
                        }
                    }
                }
            }
        }
    }

    private fun decideRoute(input: String, mode: PrivacyMode): RoutingDecision {
        return when (mode) {
            PrivacyMode.LOCAL_ONLY -> RoutingDecision.LOCAL

            PrivacyMode.CLOUD_ENHANCED -> {
                if (!isInternetAvailable()) RoutingDecision.LOCAL
                else RoutingDecision.CLOUD
            }

            PrivacyMode.HYBRID -> {
                val lo = input.lowercase()
                when {
                    !isInternetAvailable() -> RoutingDecision.LOCAL
                    input.length < 40 && LOCAL_KEYWORDS.any { lo.contains(it) } -> RoutingDecision.LOCAL
                    CLOUD_KEYWORDS.any { lo.contains(it) } && input.length > 50 -> RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK
                    input.length > 200 -> RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK
                    else -> RoutingDecision.LOCAL
                }
            }
        }
    }

    private suspend fun runLocal(input: String, memory: List<CommandMemory>): AiEngineResult =
        try { localEngine.parseIntent(input, memory) }
        catch (e: Exception) {
            Log.e(TAG, "Local engine error: ${e.message}")
            AiEngineResult(intentJson = """{"action":"unknown","commands":[]}""",
                source = AiSource.LOCAL, modelUsed = "local-error", latencyMs = 0)
        }

    private suspend fun runCloud(input: String, memory: List<CommandMemory>): AiEngineResult =
        try { cloudEngine.parseIntent(input, memory) }
        catch (e: Exception) {
            Log.e(TAG, "Cloud error: ${e.message} — falling back to local")
            runLocal(input, memory).copy(source = AiSource.FALLBACK)
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseToAiIntent(jsonStr: String): AiIntent {
        // If input has no JSON structure at all — plain text from model
        if (!jsonStr.contains('{') || !jsonStr.contains('}')) {
            // Handle "Action: X Reply: Y" format that some models return
            val replyRegex = Regex("""(?i)Reply:\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
            val replyText  = replyRegex.find(jsonStr)?.groupValues?.getOrNull(1)?.trim()
                ?: jsonStr.trim().ifBlank { null }
            return AiIntent(action = "chat", reply = replyText, commands = emptyList())
        }
        return try {
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)
            fun str(k: String) = if (obj.has(k) && !obj.get(k).isJsonNull) obj.get(k).asString else null

            val steps = if (obj.has("steps"))
                obj.getAsJsonArray("steps").map { it.asString } else emptyList()

            val commands = if (obj.has("commands")) {
                obj.getAsJsonArray("commands").mapNotNull { el ->
                    runCatching {
                        val c = el.asJsonObject
                        ShellCommand(
                            type        = CommandType.valueOf(c.get("type").asString),
                            command     = c.get("command").asString,
                            description = c.get("description")?.asString ?: ""
                        )
                    }.getOrNull()
                }
            } else emptyList()

            val extras: Map<String, String> = if (obj.has("extras") && obj.get("extras").isJsonObject) {
                gson.fromJson(obj.getAsJsonObject("extras"),
                    object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
            } else emptyMap()

            AiIntent(
                action           = str("action") ?: "unknown",
                reply            = str("reply"),
                app              = str("app"),
                contact          = str("contact"),
                message          = str("message"),
                query            = str("query"),
                delayMinutes     = if (obj.has("delayMinutes") && !obj.get("delayMinutes").isJsonNull) obj.get("delayMinutes").asLong else null,
                triggerCondition = str("triggerCondition"),
                extras           = extras,
                steps            = steps,
                commands         = commands
            )
        } catch (e: Exception) {
            Log.e(TAG, "Intent parse error: ${e.message}")
            // Best-effort: strip JSON artifacts and use raw string as reply
            val fallbackReply = jsonStr
                .replace(Regex("```json|```"), "")
                .replace(Regex("[{][^}]*[}]"), "")
                .trim()
                .ifBlank { null }
            AiIntent(action = "chat", reply = fallbackReply, commands = emptyList())
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm  = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isGoodResult(json: String): Boolean = try {
        val obj    = gson.fromJson(json, JsonObject::class.java)
        val action = obj.get("action")?.asString ?: "unknown"
        action != "unknown" && action != "parse_error" && action != "stub"
    } catch (e: Exception) { false }

    private fun buildExplanation(result: AiEngineResult): String {
        val engine = when (result.source) {
            AiSource.LOCAL    -> "🔒 Local AI (${result.modelUsed})"
            AiSource.CLOUD    -> "☁️ Cloud AI (${result.modelUsed})"
            AiSource.FALLBACK -> "⚡ Cloud Fallback (${result.modelUsed})"
        }
        return "$engine · ${result.latencyMs}ms"
    }
}

enum class RoutingDecision { LOCAL, CLOUD, LOCAL_WITH_CLOUD_FALLBACK }

data class OrchestratorResult(
    val intent: AiIntent,
    val engineResult: AiEngineResult,
    val routingDecision: RoutingDecision,
    val explanation: String
)
