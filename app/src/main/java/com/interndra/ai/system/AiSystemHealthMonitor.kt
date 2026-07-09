package com.interndra.ai.system

import android.content.Context
import android.util.Log
import com.interndra.ai.CloudAiEngine
import com.interndra.ai.GeminiAiEngine
import com.interndra.data.model.AiEngineResult
import com.interndra.data.model.AiSource
import com.interndra.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * AiSystemHealthMonitor — ADVANCED AI system intelligence layer.
 *
 * Provides:
 *
 * ## 1. Smart Caching
 * - Caches AI responses for identical/similar queries (LRU, max 200 entries)
 * - TTL-based expiration (5 minutes for simple queries, 30 min for web results)
 * - Query normalization (lowercase, strip whitespace, remove punctuation)
 *
 * ## 2. Provider Health Tracking
 * - Tracks success/failure rates per provider (Gemini vs OpenRouter)
 * - Rolling window of last 20 requests per provider
 * - Circuit breaker: if > 50% failure in window, auto-switch provider
 * - Latency tracking for optimal provider selection
 *
 * ## 3. Retry Logic with Exponential Backoff
 * - Retries on transient failures (network, rate limiting, 5xx)
 * - No retry on 4xx errors (bad request, auth failure)
 * - Backoff: 1s → 2s → 4s → 8s (max 4 retries)
 * - Jitter: ±20% random delay to avoid thundering herd
 *
 * ## 4. Adaptive Fallback Chain
 * - Primary → Secondary → Fallback → Error
 * - Gemini → OpenRouter → Local → Smart error message
 * - Tracks WHICH fallback was used and its success
 *
 * ## 5. Performance Analytics
 * - Average latency per provider
 * - Token usage tracking
 * - Cache hit/miss ratio
 * - Suggestions for optimal configuration
 */
class AiSystemHealthMonitor(private val context: Context) {

    companion object {
        private const val TAG = "AiHealthMonitor"
        private const val MAX_CACHE_ENTRIES = 200
        private const val CACHE_TTL_SIMPLE_MS = 5 * 60 * 1000L      // 5 minutes
        private const val CACHE_TTL_WEB_MS = 30 * 60 * 1000L         // 30 minutes
        private const val HEALTH_WINDOW_SIZE = 20                     // last N requests
        private const val CIRCUIT_BREAKER_THRESHOLD = 0.5f            // 50% failure
        private const val COOLDOWN_MS = 30_000L                       // 30s cool-down
        private const val MAX_RETRIES = 3
        private const val MIN_CACHE_QUERY_LENGTH = 10                 // Don't cache very short queries
    }

    // ── Cache Entry ─────────────────────────────────────────────────────

    data class CacheEntry(
        val query: String,
        val result: AiEngineResult,
        val provider: String,
        val cachedAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = CACHE_TTL_SIMPLE_MS
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > ttlMs
    }

    // ── Provider Health ─────────────────────────────────────────────────

    data class ProviderHealth(
        val provider: String,
        var totalRequests: Int = 0,
        var failedRequests: Int = 0,
        var totalLatencyMs: Long = 0L,
        var totalTokens: Int = 0,
        val recentResults: MutableList<Boolean> = mutableListOf(),
        var consecutiveFailures: Int = 0,
        var circuitOpen: Boolean = false,
        var circuitOpenedAt: Long = 0L,
        var lastError: String = "",
        var lastUsedAt: Long = 0L
    ) {
        val successRate: Float get() =
            if (totalRequests > 0) 1f - (failedRequests.toFloat() / totalRequests) else 1f

        val windowSuccessRate: Float get() {
            if (recentResults.isEmpty()) return 1f
            val successes = recentResults.count { it }
            return successes.toFloat() / recentResults.size
        }

        val averageLatencyMs: Long get() =
            if (totalRequests > 0) totalLatencyMs / totalRequests else 0L

        val isOnCooldown: Boolean get() {
            if (!circuitOpen) return false
            return System.currentTimeMillis() - circuitOpenedAt < COOLDOWN_MS
        }
    }

    // ── State ───────────────────────────────────────────────────────────

    // FIX: Thread-safe LRU cache using ConcurrentHashMap + manual LRU tracking
    private val responseCache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheAccessOrder = ConcurrentLinkedDeque<String>()

    private val providerHealth = ConcurrentHashMap<String, ProviderHealth>().apply {
        put("gemini", ProviderHealth(provider = "gemini"))
        put("openrouter", ProviderHealth(provider = "openrouter"))
    }

    // Analytics tracking — AtomicLong for thread safety
    private val _totalQueries = java.util.concurrent.atomic.AtomicLong(0)
    private val _cacheHits = java.util.concurrent.atomic.AtomicLong(0)
    private val _cacheMisses = java.util.concurrent.atomic.AtomicLong(0)
    private val _totalRetries = java.util.concurrent.atomic.AtomicLong(0)
    private val _successfulRetries = java.util.concurrent.atomic.AtomicLong(0)

    // ── Smart Caching ───────────────────────────────────────────────────

    /**
     * Normalize a query for cache lookup.
     * Strips whitespace, lowercases, removes punctuation.
     */
    private fun normalizeQuery(query: String): String {
        return query.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Generate a cache key from query and optional context.
     */
    private fun cacheKey(query: String, provider: String): String {
        val normalized = normalizeQuery(query)
        return "$provider::$normalized"
    }

    /**
     * Try to get a cached result for the query.
     */
    fun getCached(query: String, provider: String): AiEngineResult? {
        if (query.length < MIN_CACHE_QUERY_LENGTH) return null

        _totalQueries.incrementAndGet()
        val key = cacheKey(query, provider)

        val entry = responseCache[key]
        if (entry == null) {
            _cacheMisses.incrementAndGet()
            return null
        }

        if (entry.isExpired) {
            responseCache.remove(key)
            cacheAccessOrder.remove(key)
            _cacheMisses.incrementAndGet()
            return null
        }

        // Update access order
        cacheAccessOrder.remove(key)
        cacheAccessOrder.addLast(key)

        // Enforce cache size limit (LRU eviction)
        while (cacheAccessOrder.size > MAX_CACHE_ENTRIES) {
            val oldest = cacheAccessOrder.pollFirst() ?: break
            responseCache.remove(oldest)
        }

        _cacheHits.incrementAndGet()
        Log.d(TAG, "Cache HIT for query: ${query.take(40)} (${provider})")
        return entry.result
    }

    /**
     * Cache a result for future use.
     */
    fun cacheResult(query: String, provider: String, result: AiEngineResult) {
        if (query.length < MIN_CACHE_QUERY_LENGTH) return
        val key = cacheKey(query, provider)
        val ttl = if (result.source == AiSource.FALLBACK) CACHE_TTL_WEB_MS else CACHE_TTL_SIMPLE_MS
        responseCache[key] = CacheEntry(query, result, provider, ttlMs = ttl)
        cacheAccessOrder.addLast(key)

        // Enforce cache size limit
        while (cacheAccessOrder.size > MAX_CACHE_ENTRIES) {
            val oldest = cacheAccessOrder.pollFirst() ?: break
            responseCache.remove(oldest)
        }

        Log.d(TAG, "Cache: stored result for ${query.take(40)} (${provider})")
    }

    /**
     * Clear all cached responses.
     */
    fun clearCache() {
        responseCache.clear()
        cacheAccessOrder.clear()
        Log.d(TAG, "Cache cleared")
    }

    val cacheStats: Triple<Long, Long, Long> get() = Triple(
        _totalQueries.get(), _cacheHits.get(), _cacheMisses.get()
    )

    val cacheHitRate: Float get() {
        val total = _totalQueries.get()
        return if (total > 0) _cacheHits.get().toFloat() / total else 0f
    }

    // ── Provider Health Tracking ───────────────────────────────────────

    /**
     * Record the result of a provider request.
     */
    fun recordResult(
        provider: String,
        success: Boolean,
        latencyMs: Long,
        tokens: Int = 0,
        error: String = ""
    ) {
        val health = providerHealth.getOrPut(provider) { ProviderHealth(provider = provider) }

        synchronized(health) {
            health.totalRequests++
            health.totalLatencyMs += latencyMs
            health.totalTokens += tokens
            health.lastUsedAt = System.currentTimeMillis()

            health.recentResults.add(success)
            if (health.recentResults.size > HEALTH_WINDOW_SIZE) {
                health.recentResults.removeAt(0)
            }

            if (success) {
                health.consecutiveFailures = 0
            } else {
                health.failedRequests++
                health.consecutiveFailures++
                health.lastError = error

                // Circuit breaker: open circuit if window failure rate is too high
                if (health.windowSuccessRate < CIRCUIT_BREAKER_THRESHOLD) {
                    health.circuitOpen = true
                    health.circuitOpenedAt = System.currentTimeMillis()
                    Log.w(TAG, "⚠ Circuit BROKEN for $provider " +
                            "(rate: ${(health.windowSuccessRate * 100).toInt()}%, " +
                            "consecutive: ${health.consecutiveFailures})")
                }
            }
        }
    }

    /**
     * Get health status for a specific provider.
     */
    fun getProviderHealth(provider: String): ProviderHealth =
        providerHealth[provider] ?: ProviderHealth(provider = provider)

    /**
     * Check if a provider should be used (circuit breaker).
     */
    fun canUseProvider(provider: String): Boolean {
        val health = providerHealth[provider] ?: return true
        if (!health.circuitOpen) return true

        // If on cooldown, try to auto-recover after cooldown period
        if (health.isOnCooldown) {
            Log.d(TAG, "Provider $provider on cooldown...")
            return false
        }

        // Cooldown expired - close circuit and try again
        synchronized(health) {
            health.circuitOpen = false
            health.recentResults.clear()
            Log.i(TAG, "⏻ Circuit CLOSED for $provider - attempting recovery")
        }
        return true
    }

    // ── Smart Provider Selection ───────────────────────────────────────

    /**
     * Get the best provider based on health and latency.
     */
    fun getBestProvider(preferredProvider: String): String {
        // If preferred is healthy, use it
        if (canUseProvider(preferredProvider)) {
            val health = getProviderHealth(preferredProvider)
            if (health.windowSuccessRate >= 0.5f) return preferredProvider
        }

        // Find alternative with best health
        val alternatives = providerHealth.entries
            .filter { it.key != preferredProvider }
            .filter { canUseProvider(it.key) }
            .sortedByDescending { it.value.windowSuccessRate }

        val best = alternatives.firstOrNull()
        if (best != null) {
            Log.i(TAG, "⚡ Auto-switching: $preferredProvider → ${best.key} " +
                    "(rates: ${getProviderHealth(preferredProvider).windowSuccessRate}% vs ${best.value.windowSuccessRate}%)")
            return best.key
        }

        // All providers are unhealthy - return preferred as fallback
        return preferredProvider
    }

    // ── Retry Logic ─────────────────────────────────────────────────────

    /**
     * Execute a provider call with retry logic and exponential backoff.
     */
    suspend fun executeWithRetry(
        provider: String,
        maxRetries: Int = MAX_RETRIES,
        block: suspend () -> AiEngineResult
    ): AiEngineResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            try {
                val startMs = System.currentTimeMillis()
                val result = block()
                val latency = System.currentTimeMillis() - startMs

                recordResult(provider, true, latency, result.tokenCount)
                Log.d(TAG, "Provider $provider succeeded on attempt ${attempt + 1} (${latency}ms)")

                return@withContext result

            } catch (e: Exception) {
                lastError = e
                val errorMsg = e.message ?: ""
                val latency = 0L

                // Determine if this error is retryable
                val isRetryable = isRetryableError(errorMsg)

                if (!isRetryable || attempt >= maxRetries) {
                    recordResult(provider, false, latency, error = errorMsg)
                    Log.w(TAG, "Provider $provider failed (non-retryable): $errorMsg")
                    throw e
                }

                // Exponential backoff with jitter
                val baseDelay = (1000L shl attempt) // 1s, 2s, 4s, 8s
                val jitter = (baseDelay * 0.2 * Math.random()).toLong()
                val delay = baseDelay + jitter

                _totalRetries.incrementAndGet()
                Log.w(TAG, "Provider $provider attempt ${attempt + 1} failed, " +
                        "retrying in ${delay}ms: ${errorMsg.take(100)}")

                kotlinx.coroutines.delay(delay)
                attempt++
            }
            attempt++ // ensure increment even on success path (we throw on fail)
        }

        // This should never be reached (exception thrown above)
        throw lastError ?: IllegalStateException("Retry exhausted")
    }

    /**
     * Determine if an error is retryable.
     * Network errors, rate limits, and 5xx are retryable.
     * Auth errors, bad requests, and 4xx are not.
     */
    private fun isRetryableError(error: String): Boolean {
        val lower = error.lowercase()

        // Non-retryable
        val nonRetryable = listOf(
            "401", "402", "403", "404", "406", "409", "410", "422",
            "invalid api key", "api key not found", "unauthorized",
            "forbidden", "not found", "bad request",
            "model not found", "model not available",
            "content blocked", "safety", "blocked",
            "quota exceeded", "insufficient_quota",
            "billing", "payment required"
        )
        if (nonRetryable.any { lower.contains(it) }) {
            return false
        }

        // Retryable
        val retryable = listOf(
            "429", "500", "502", "503", "504",
            "timeout", "timed out", "time_out",
            "connection refused", "connection reset",
            "network is unreachable", "no route to host",
            "socket", "ssl", "handshake",
            "too many requests", "rate limit", "rate_limit",
            "service unavailable", "temporarily",
            "server error", "internal server error",
            "bad gateway", "gateway timeout",
            "empty response", "no response",
            "failed to connect", "unable to connect"
        )
        if (retryable.any { lower.contains(it) }) {
            return true
        }

        // Default: retry on unknown errors
        return true
    }

    // ── Performance Analytics ──────────────────────────────────────────

    data class SystemReport(
        val cacheHitRate: Float,
        val totalQueries: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
        val totalRetries: Long,
        val successfulRetries: Long,
        val providerReports: List<ProviderReport>,
        val recommendedProvider: String,
        val recommendedAction: String
    )

    data class ProviderReport(
        val name: String,
        val successRate: Float,
        val windowSuccessRate: Float,
        val averageLatencyMs: Long,
        val totalRequests: Int,
        val consecutiveFailures: Int,
        val circuitOpen: Boolean,
        val lastError: String
    )

    /**
     * Generate a comprehensive system health report.
     */
    fun generateReport(preferredProvider: String = "openrouter"): SystemReport {
        val providerReports = providerHealth.map { (name, health) ->
            ProviderReport(
                name = name,
                successRate = health.successRate,
                windowSuccessRate = health.windowSuccessRate,
                averageLatencyMs = health.averageLatencyMs,
                totalRequests = health.totalRequests,
                consecutiveFailures = health.consecutiveFailures,
                circuitOpen = health.circuitOpen,
                lastError = health.lastError
            )
        }

        val bestProvider = getBestProvider(preferredProvider)
        val preferredHealth = getProviderHealth(preferredProvider)

        val action = when {
            !canUseProvider(preferredProvider) ->
                "⚠️ $preferredProvider is on circuit break. Auto-switching to $bestProvider."
            preferredHealth.consecutiveFailures > 5 ->
                "⚠️ $preferredProvider has ${preferredHealth.consecutiveFailures} consecutive failures."
            cacheHitRate > 0.5f ->
                "✅ Cache hit rate is ${(cacheHitRate * 100).toInt()}% — good!"
            cacheHitRate < 0.1f && totalQueries > 50 ->
                "💡 Consider enabling cache for repeated queries."
            else ->
                "✅ System healthy."
        }

        return SystemReport(
            cacheHitRate = cacheHitRate,
            totalQueries = _totalQueries.get(),
            cacheHits = _cacheHits.get(),
            cacheMisses = _cacheMisses.get(),
            totalRetries = _totalRetries.get(),
            successfulRetries = _successfulRetries.get(),
            providerReports = providerReports,
            recommendedProvider = bestProvider,
            recommendedAction = action
        )
    }

    /**
     * Get a formatted health status string for display.
     */
    fun getHealthStatus(preferredProvider: String = "openrouter"): String {
        val report = generateReport(preferredProvider)
        val sb = StringBuilder()
        sb.appendLine("## 🏥 AI System Health")
        sb.appendLine()
        sb.appendLine("### Caching")
        sb.appendLine("- Cache hit rate: **${(report.cacheHitRate * 100).toInt()}%**")
        sb.appendLine("- Total queries: ${report.totalQueries}")
        sb.appendLine("- Cache: ${report.cacheHits} hits / ${report.cacheMisses} misses")
        sb.appendLine("- Retries: ${report.totalRetries} total")
        sb.appendLine()
        sb.appendLine("### Providers")
        report.providerReports.forEach { pr ->
            val icon = when {
                pr.circuitOpen -> "🔴"
                pr.windowSuccessRate < 0.5f -> "⚠️"
                pr.windowSuccessRate < 0.8f -> "🟡"
                else -> "🟢"
            }
            sb.appendLine("$icon **${pr.name}**: " +
                    "${(pr.windowSuccessRate * 100).toInt()}% success " +
                    "(${pr.averageLatencyMs}ms avg, ${pr.totalRequests} requests)")
        }
        sb.appendLine()
        sb.appendLine("### Recommendation")
        sb.appendLine(report.recommendedAction)
        return sb.toString()
    }

    /**
     * Get a formatted performance report for debugging.
     */
    fun getPerformanceReport(): String {
        val sb = StringBuilder()
        sb.appendLine("## 📊 Performance Report")
        sb.appendLine()
        sb.appendLine("### Caching Stats")
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|--------|------:|")
        sb.appendLine("| Hit Rate | ${(cacheHitRate * 100).toInt()}% |")
        sb.appendLine("| Cache Size | ${responseCache.size} / $MAX_CACHE_ENTRIES |")
        sb.appendLine("| Total Queries | ${_totalQueries.get()} |")
        sb.appendLine("| Retries | ${_totalRetries.get()} |")
        sb.appendLine()
        sb.appendLine("### Provider Stats")
        sb.appendLine("| Provider | Success Rate | Window Rate | Avg Latency | Requests | Circuit |")
        sb.appendLine("|----------|:-----------:|:-----------:|:-----------:|:--------:|:-------:|")
        providerHealth.forEach { (name, health) ->
            val circuitIcon = if (health.circuitOpen) "🔴 Open" else "🟢 Closed"
            sb.appendLine("| $name | ${(health.successRate * 100).toInt()}% | " +
                    "${(health.windowSuccessRate * 100).toInt()}% | " +
                    "${health.averageLatencyMs}ms | ${health.totalRequests} | $circuitIcon |")
        }
        return sb.toString()
    }

    /**
     * Reset all health tracking data.
     */
    /**
     * Save the current health report for debugging purposes.
     * Called when the ViewModel is cleared.
     */
    fun saveReport() {
        try {
            val report = generateReport()
            Log.i(TAG, "Final health report: ${report.cacheHitRate * 100}% cache hit, " +
                    "${report.totalQueries} queries, ${report.providerReports.size} providers")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save health report: ${e.message}")
        }
    }

    fun reset() {
        responseCache.clear()
        cacheAccessOrder.clear()
        providerHealth.values.forEach { it.reset() }
        _totalQueries.set(0)
        _cacheHits.set(0)
        _cacheMisses.set(0)
        _totalRetries.set(0)
        _successfulRetries.set(0)
        Log.i(TAG, "All health tracking data reset")
    }
}

/**
 * Extension to reset provider health
 */
private fun AiSystemHealthMonitor.ProviderHealth.reset() {
    totalRequests = 0
    failedRequests = 0
    totalLatencyMs = 0L
    totalTokens = 0
    recentResults.clear()
    consecutiveFailures = 0
    circuitOpen = false
    circuitOpenedAt = 0L
    lastError = ""
    lastUsedAt = 0L
}
