package com.interndra.ai

import android.content.Context
import android.util.Log
import com.interndra.data.model.*
import com.interndra.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LocalAiEngine — runs Qwen2.5-3B-Instruct-Q4_K_M.gguf via llama.cpp JNI.
 *
 * UPGRADES over original:
 *  1. PRIMARY model path is context.filesDir/models/ (no WRITE_EXTERNAL_STORAGE needed)
 *  2. Legacy /sdcard/ paths still checked as fallback so existing users keep their model
 *  3. File-size validation (must be > 500 MB) to reject partial downloads
 *  4. nativeLibLoaded guard — app never crashes if JNI .so is absent; graceful fallback only
 *  5. Coroutine-safe: all heavy ops run on Dispatchers.IO, no runBlocking
 *  6. Prompt template: Qwen2.5 ChatML format (<|im_start|>/<|im_end|>)
 *  7. extractJson() strips code fences and finds outermost { } robustly
 */
class LocalAiEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalAiEngine"

        // ── Model filenames (single source of truth) ────────────────────────
        const val DEFAULT_MODEL_FILENAME = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
        const val SMALL_MODEL_FILENAME   = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

        // ── Min file size: 500 MB for 3B model, 200 MB for 0.5B ───────────
        const val MIN_MODEL_BYTES_3B  = 500L * 1024 * 1024
        const val MIN_MODEL_BYTES_05B = 200L * 1024 * 1024

        // ── Search paths (ordered: preferred first) ────────────────────────
        // App-private storage first (no permission needed on API 29+)
        // then legacy external paths for users who already downloaded
        fun getSearchPaths(context: Context) = listOf(
            "${context.filesDir.absolutePath}/models",          // PRIMARY — no permission
            "/sdcard/INTERNDRA/models",                         // current canonical path
            "/sdcard/INTENTRA/models",                          // legacy fallback (pre-rename)
            "/sdcard/Download",
            "/sdcard/Android/data/com.interndra/files/models"
        )

        // ── JNI library loading ────────────────────────────────────────────
        private var nativeLibLoaded = false
        init {
            nativeLibLoaded = try {
                System.loadLibrary("interndra_llama")
                Log.i(TAG, "llama.cpp native library loaded ✓")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "llama.cpp stub — local AI uses rule-based fallback")
                false
            }
        }

        // ── JNI function declarations ─────────────────────────────────────
        @JvmStatic private external fun nativeInitImpl(modelPath: String, nThreads: Int, nCtx: Int): Long
        @JvmStatic private external fun nativeInferImpl(handle: Long, prompt: String, maxTokens: Int, temp: Float): String
        @JvmStatic private external fun nativeFreeImpl(handle: Long)
        @JvmStatic private external fun nativeIsLoadedImpl(handle: Long): Boolean

        fun nativeInit(modelPath: String, nThreads: Int, nCtx: Int): Long =
            if (nativeLibLoaded) nativeInitImpl(modelPath, nThreads, nCtx) else 0L

        fun nativeInfer(handle: Long, prompt: String, maxTokens: Int, temp: Float): String =
            if (nativeLibLoaded && handle != 0L) nativeInferImpl(handle, prompt, maxTokens, temp)
            else """{"action":"stub","reply":"Local model not loaded.","commands":[]}"""

        fun nativeFree(handle: Long) {
            if (nativeLibLoaded && handle != 0L) nativeFreeImpl(handle)
        }

        fun nativeIsLoaded(handle: Long): Boolean =
            nativeLibLoaded && handle != 0L && nativeIsLoadedImpl(handle)
    }

    private var modelHandle: Long = 0L
    private var isReady = false
    private var loadedModelPath = ""

    // ── Model loading ──────────────────────────────────────────────────────
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        val modelPath = findModelPath()
        if (modelPath == null) {
            Log.w(TAG, "No GGUF model found. Download via Settings → Download Local Model")
            return@withContext false
        }
        try {
            Log.i(TAG, "Loading model: $modelPath")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val handle  = nativeInit(modelPath, threads, 2048)
            return@withContext if (handle != 0L && nativeIsLoaded(handle)) {
                if (modelHandle != 0L) nativeFree(modelHandle)
                modelHandle     = handle
                loadedModelPath = modelPath
                isReady         = true
                Log.i(TAG, "Model loaded ✓  handle=$handle  threads=$threads")
                true
            } else {
                Log.e(TAG, "nativeInit returned invalid handle — JNI .so may be missing")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}")
            false
        }
    }

    fun unload() {
        if (modelHandle != 0L) {
            nativeFree(modelHandle)
            modelHandle = 0L
            isReady     = false
            Log.i(TAG, "Model unloaded")
        }
    }

    fun isModelReady() = isReady && modelHandle != 0L

    fun getModelInfo(): String = when {
        isReady       -> "✅ Loaded: ${File(loadedModelPath).name}"
        isModelDownloaded() -> "⚠️ Downloaded but not loaded yet"
        else          -> "❌ Model not found — download via Settings"
    }

    // ── Inference ──────────────────────────────────────────────────────────
    suspend fun parseIntent(
        userInput: String,
        memory: List<CommandMemory>,
        runtimeContext: String = ""
    ): AiEngineResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        if (!isModelReady()) {
            val loaded = loadModel()
            if (!loaded) {
                return@withContext AiEngineResult(
                    intentJson = buildRuleBasedFallback(userInput),
                    source     = AiSource.LOCAL,
                    isSuccess  = true,
                    modelUsed  = "rule-based-fallback",
                    latencyMs  = System.currentTimeMillis() - startMs
                )
            }
        }

        return@withContext try {
            val prompt = buildPrompt(userInput, memory, runtimeContext)
            val raw    = nativeInfer(modelHandle, prompt, maxTokens = 512, temp = 0.1f)
            val json   = extractJson(raw)
            Log.d(TAG, "Local inference ${System.currentTimeMillis() - startMs}ms")
            AiEngineResult(
                intentJson = json,
                source     = AiSource.LOCAL,
                modelUsed  = "Qwen2.5-3B Q4_K_M",
                latencyMs  = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            AiEngineResult(
                intentJson = buildRuleBasedFallback(userInput),
                source     = AiSource.LOCAL,
                isSuccess  = true,
                modelUsed  = "rule-based-fallback",
                latencyMs  = System.currentTimeMillis() - startMs
            )
        }
    }

    // ── Prompt builder — Qwen2.5 ChatML format ────────────────────────────
    private fun buildPrompt(userInput: String, memory: List<CommandMemory>, runtimeContext: String = ""): String = buildString {
        val basePrompt = Constants.aiSystemPrompt(runtimeContext)
        val systemPrompt = if (JailbreakEngine.activeLevel != JailbreakLevel.OFF) {
            JailbreakEngine.injectJailbreak(basePrompt, JailbreakEngine.activeLevel)
        } else {
            basePrompt
        }
        val obfuscatedInput = if (JailbreakEngine.activeLevel != JailbreakLevel.OFF) {
            JailbreakEngine.obfuscateInput(userInput, JailbreakEngine.activeLevel)
        } else {
            userInput
        }
        append("<|im_start|>system\n$systemPrompt\n<|im_end|>\n")
        memory.takeLast(5).forEach { m ->
            append("<|im_start|>user\n${m.userInput}\n<|im_end|>\n")
            append("<|im_start|>assistant\nAction: ${m.actionType}\n<|im_end|>\n")
        }
        append("<|im_start|>user\n$obfuscatedInput\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    // ── JSON extractor — strip code fences + find outermost {} ───────────
    private fun extractJson(raw: String): String {
        val clean = raw
            .removePrefix("<|im_end|>").trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end   = clean.lastIndexOf('}')
        return if (start >= 0 && end > start) clean.substring(start, end + 1)
        else clean.ifBlank { """{"action":"unknown","commands":[]}""" }
    }

    // ── Rule-based fallback (runs without any model) ──────────────────────
    private fun buildRuleBasedFallback(input: String): String {
        val lo = input.lowercase().trim()
        return when {
            lo in listOf("hi","hello","hey","hii","helo") ->
                """{"action":"greeting","reply":"Hello! I'm INTERNDRA. How can I help you today?","commands":[]}"""
            lo.contains("battery") ->
                """{"action":"battery_info","reply":"Checking battery status...","commands":[{"type":"ADB_SHELL","command":"dumpsys battery | grep -E 'level|status|temperature'","description":"Battery info"}]}"""
            lo.contains("screenshot") || lo.contains("capture") ->
                """{"action":"screenshot","reply":"Taking screenshot...","commands":[{"type":"ADB_SHELL","command":"screencap -p /sdcard/INTERNDRA/screenshot_${System.currentTimeMillis()}.png","description":"Capture screen"}]}"""
            lo.contains("storage") || lo.contains("space") ->
                """{"action":"storage_info","reply":"Checking storage...","commands":[{"type":"ADB_SHELL","command":"df -h /sdcard","description":"Storage info"}]}"""
            lo.contains("whatsapp") ->
                """{"action":"open_app","reply":"Opening WhatsApp...","app":"whatsapp","commands":[{"type":"ANDROID_INTENT","command":"open_app:com.whatsapp","description":"Open WhatsApp"}]}"""
            lo.contains("youtube") ->
                """{"action":"open_app","reply":"Opening YouTube...","app":"youtube","commands":[{"type":"ANDROID_INTENT","command":"open_app:com.google.android.youtube","description":"Open YouTube"}]}"""
            lo.contains("settings") ->
                """{"action":"open_app","reply":"Opening Settings...","app":"settings","commands":[{"type":"ANDROID_INTENT","command":"open_app:com.android.settings","description":"Open Settings"}]}"""
            lo.contains("wifi") || lo.contains("wi-fi") ->
                """{"action":"wifi_info","reply":"Checking WiFi status...","commands":[{"type":"ADB_SHELL","command":"dumpsys wifi | grep 'mNetworkInfo\\|SSID\\|bssid'","description":"WiFi info"}]}"""
            lo.contains("volume up") ->
                """{"action":"volume_up","reply":"Increasing volume...","commands":[{"type":"ADB_SHELL","command":"input keyevent 24","description":"Volume up"}]}"""
            lo.contains("volume down") ->
                """{"action":"volume_down","reply":"Decreasing volume...","commands":[{"type":"ADB_SHELL","command":"input keyevent 25","description":"Volume down"}]}"""
            lo.contains("list files") || lo == "ls" || lo.startsWith("ls ") ->
                """{"action":"list_files","reply":"Listing files...","commands":[{"type":"ADB_SHELL","command":"ls -la /sdcard/Download/","description":"List Downloads"}]}"""
            lo.contains("time") || lo.contains("date") ->
                """{"action":"get_time","reply":"Checking time...","commands":[{"type":"ADB_SHELL","command":"date","description":"Current date/time"}]}"""
            lo.contains("git status") ->
                """{"action":"git_status","reply":"Checking git status...","commands":[{"type":"ADB_SHELL","command":"cd /sdcard && git status","description":"Git status"}]}"""
            lo.contains("ip address") || lo.contains("ipconfig") ->
                """{"action":"network_info","reply":"Getting network info...","commands":[{"type":"ADB_SHELL","command":"ifconfig wlan0","description":"Network info"}]}"""
            else ->
                """{"action":"fallback","reply":"⚠️ Unable to process this request.\n\n**Possible causes:**\n- Cloud AI (Gemini/OpenRouter) may have returned an error\n- Check your API key in Settings\n- Try a different AI model\n- Make sure you have internet connection\n\n*Tip: If you just saved your API key, try switching to Cloud Enhanced mode in Privacy settings.*","commands":[]}"""
        }
    }

    // ── Model file finder with size validation ────────────────────────────
    fun findModelPath(): String? {
        val searchPaths = getSearchPaths(context)
        for (dir in searchPaths) {
            val file = File("$dir/$DEFAULT_MODEL_FILENAME")
            if (file.exists() && file.length() >= MIN_MODEL_BYTES_3B) {
                return file.absolutePath
            }
            // Also accept the smaller model
            val small = File("$dir/$SMALL_MODEL_FILENAME")
            if (small.exists() && small.length() >= MIN_MODEL_BYTES_05B) {
                return small.absolutePath
            }
        }
        return null
    }

    fun isModelDownloaded() = findModelPath() != null
}
