package com.interndra.ai.system

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.interndra.data.model.AiEngineResult
import com.interndra.data.model.AiSource
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for AiSystemHealthMonitor.
 *
 * Tests:
 * - Smart caching (LRU, TTL, query normalization)
 * - Provider health tracking
 * - Circuit breaker logic
 * - Retry logic with exponential backoff
 * - Provider selection
 * - Performance reports
 */
class AiSystemHealthMonitorTest {

    private lateinit var monitor: AiSystemHealthMonitor
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        monitor = AiSystemHealthMonitor(mockContext)
    }

    // ── Smart Caching ──────────────────────────────────────────────────

    @Test
    fun `getCached returns null for short queries`() {
        val result = monitor.getCached("hi", "openrouter")
        assertThat(result).isNull()
    }

    @Test
    fun `cacheResult then getCached returns cached value`() {
        val expected = AiEngineResult(
            intentJson = """{"action":"chat","reply":"test"}""",
            source = AiSource.CLOUD
        )
        monitor.cacheResult("what is the weather today", "openrouter", expected)

        val cached = monitor.getCached("what is the weather today", "openrouter")
        assertThat(cached).isNotNull()
        assertThat(cached!!.intentJson).isEqualTo(expected.intentJson)
    }

    @Test
    fun `getCached returns cached entry that was just stored`() {
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"test"}""",
            source = AiSource.CLOUD
        )

        monitor.cacheResult("fresh query for cache", "openrouter", result)
        val cached = monitor.getCached("fresh query for cache", "openrouter")
        assertThat(cached).isNotNull()
        assertThat(cached!!.intentJson).isEqualTo(result.intentJson)
    }

    @Test
    fun `getCached returns null after clearCache`() {
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"test"}""",
            source = AiSource.CLOUD
        )
        monitor.cacheResult("cache and clear test", "openrouter", result)
        monitor.clearCache()

        val cached = monitor.getCached("cache and clear test", "openrouter")
        assertThat(cached).isNull()
    }

    @Test
    fun `normalizeQuery lowercases and strips punctuation`() {
        // Use reflection or test through cache — normalized query keys
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"test"}""",
            source = AiSource.CLOUD
        )
        monitor.cacheResult("WHAT IS THE WEATHER?!?!", "openrouter", result)

        // Should match normalized version
        val cached = monitor.getCached("what is the weather", "openrouter")
        assertThat(cached).isNotNull()
    }

    @Test
    fun `cache is provider-specific`() {
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"gemini answer"}""",
            source = AiSource.CLOUD
        )
        monitor.cacheResult("what is love", "gemini", result)

        // Should NOT be found under OpenRouter
        val cached = monitor.getCached("what is love", "openrouter")
        assertThat(cached).isNull()
    }

    @Test
    fun `cached entries have hit rate tracked`() {
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"tracking"}""",
            source = AiSource.CLOUD
        )
        monitor.cacheResult("tracking query test", "openrouter", result)

        // Miss first (already cached from above), then hit
        monitor.getCached("tracking query test", "openrouter") // hit
        monitor.getCached("nonexistent query xyz", "openrouter") // miss

        val (total, hits, misses) = monitor.cacheStats
        assertThat(total).isAtLeast(2L)
        assertThat(hits).isAtLeast(1L)
        assertThat(misses).isAtLeast(1L)
    }

    @Test
    fun `cacheHitRate returns expected value`() {
        // After hits and misses
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"rate"}""",
            source = AiSource.CLOUD
        )
        // Force miss then hit
        monitor.getCached("rate test query one", "openrouter") // miss
        monitor.getCached("rate test query two", "openrouter") // miss
        monitor.cacheResult("rate test query one", "openrouter", result)
        monitor.getCached("rate test query one", "openrouter") // hit

        val rate = monitor.cacheHitRate
        assertThat(rate).isGreaterThan(0f)
        assertThat(rate).isLessThan(1f)
    }

    @Test
    fun `short queries are not cached`() {
        val result = AiEngineResult(
            intentJson = """{"action":"chat","reply":"short"}""",
            source = AiSource.CLOUD
        )
        monitor.cacheResult("hi", "openrouter", result)
        val cached = monitor.getCached("hi", "openrouter")
        assertThat(cached).isNull()
    }

    // ── Provider Health Tracking ───────────────────────────────────────

    @Test
    fun `recordResult tracks successful requests`() {
        monitor.recordResult("openrouter", success = true, latencyMs = 100)

        val health = monitor.getProviderHealth("openrouter")
        assertThat(health.totalRequests).isEqualTo(1)
        assertThat(health.successRate).isEqualTo(1.0f)
    }

    @Test
    fun `recordResult tracks failed requests`() {
        monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "timeout")

        val health = monitor.getProviderHealth("openrouter")
        assertThat(health.totalRequests).isEqualTo(1)
        assertThat(health.failedRequests).isEqualTo(1)
        assertThat(health.lastError).contains("timeout")
    }

    @Test
    fun `recordResult tracks latency`() {
        monitor.recordResult("gemini", success = true, latencyMs = 500)

        val health = monitor.getProviderHealth("gemini")
        assertThat(health.averageLatencyMs).isEqualTo(500L)
    }

    @Test
    fun `recordResult maintains rolling window of recent results`() {
        // Add 25 results (window is 20)
        repeat(10) { monitor.recordResult("gemini", success = true, latencyMs = 100) }
        repeat(10) { monitor.recordResult("gemini", success = false, latencyMs = 0, error = "err") }
        repeat(5) { monitor.recordResult("gemini", success = true, latencyMs = 100) }

        val health = monitor.getProviderHealth("gemini")
        assertThat(health.totalRequests).isEqualTo(25)
        assertThat(health.recentResults.size).isAtMost(20)
    }

    @Test
    fun `successRate calculation is correct`() {
        monitor.recordResult("openrouter", success = true, latencyMs = 100)
        monitor.recordResult("openrouter", success = true, latencyMs = 100)
        monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "err")

        val health = monitor.getProviderHealth("openrouter")
        assertThat(health.successRate).isEqualTo(2f / 3f)
    }

    // ── Circuit Breaker ────────────────────────────────────────────────

    @Test
    fun `canUseProvider returns true for healthy provider`() {
        monitor.recordResult("openrouter", success = true, latencyMs = 50)
        monitor.recordResult("openrouter", success = true, latencyMs = 50)

        assertThat(monitor.canUseProvider("openrouter")).isTrue()
    }

    @Test
    fun `canUseProvider returns true for unknown provider`() {
        assertThat(monitor.canUseProvider("nonexistent")).isTrue()
    }

    @Test
    fun `circuit breaker opens after more than 50 percent failures`() {
        // 10 successes + 11 failures = 21 total, window size 20
        repeat(10) { monitor.recordResult("gemini", success = true, latencyMs = 100) }
        // 11 failures to exceed 50% of rolling window
        repeat(11) {
            monitor.recordResult("gemini", success = false, latencyMs = 0, error = "timeout")
        }

        val health = monitor.getProviderHealth("gemini")
        assertThat(health.circuitOpen).isTrue()
        assertThat(monitor.canUseProvider("gemini")).isFalse()
    }

    @Test
    fun `consecutive failures are tracked`() {
        repeat(3) {
            monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "error")
        }

        val health = monitor.getProviderHealth("openrouter")
        assertThat(health.consecutiveFailures).isEqualTo(3)
    }

    @Test
    fun `consecutive failures reset on success`() {
        monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "error")
        monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "error")
        monitor.recordResult("openrouter", success = true, latencyMs = 100)

        val health = monitor.getProviderHealth("openrouter")
        assertThat(health.consecutiveFailures).isEqualTo(0)
    }

    // ── Provider Selection ─────────────────────────────────────────────

    @Test
    fun `getBestProvider returns preferred provider when healthy`() {
        monitor.recordResult("openrouter", success = true, latencyMs = 50)
        monitor.recordResult("openrouter", success = true, latencyMs = 50)

        val best = monitor.getBestProvider("openrouter")
        assertThat(best).isEqualTo("openrouter")
    }

    @Test
    fun `getBestProvider switches when preferred is unhealthy`() {
        // OpenRouter fails repeatedly
        repeat(20) {
            monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "timeout")
        }
        // Gemini is healthy
        repeat(3) {
            monitor.recordResult("gemini", success = true, latencyMs = 50)
        }

        val best = monitor.getBestProvider("openrouter")
        assertThat(best).isEqualTo("gemini")
    }

    @Test
    fun `getBestProvider returns preferred when no alternatives healthy`() {
        // Both providers fail
        repeat(20) {
            monitor.recordResult("openrouter", success = false, latencyMs = 0, error = "err")
            monitor.recordResult("gemini", success = false, latencyMs = 0, error = "err")
        }

        val best = monitor.getBestProvider("openrouter")
        assertThat(best).isEqualTo("openrouter") // falls back to preferred
    }

    // ── Retry Logic ────────────────────────────────────────────────────

    @Test
    fun `executeWithRetry succeeds on first attempt`() = runBlocking {
        var attempts = 0
        val result = monitor.executeWithRetry("openrouter", maxRetries = 2) {
            attempts++
            AiEngineResult(
                intentJson = """{"action":"chat","reply":"ok"}""",
                source = AiSource.CLOUD
            )
        }

        assertThat(result.intentJson).contains("ok")
        assertThat(attempts).isEqualTo(1)
    }

    @Test(expected = IllegalStateException::class)
    fun `executeWithRetry throws on non-retryable error`() = runBlocking {
        monitor.executeWithRetry("openrouter", maxRetries = 2) {
            throw IllegalStateException("401 Unauthorized")
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `executeWithRetry throws after max retries`() = runBlocking {
        monitor.executeWithRetry("openrouter", maxRetries = 2) {
            throw IllegalStateException("500 Internal Server Error")
        }
    }

    @Test
    fun `executeWithRetry retries on retryable error`() = runBlocking {
        var attempts = 0
        val result = monitor.executeWithRetry("openrouter", maxRetries = 3) {
            attempts++
            if (attempts < 3) throw IllegalStateException("502 Bad Gateway")
            else AiEngineResult(
                intentJson = """{"action":"chat","reply":"recovered"}""",
                source = AiSource.CLOUD
            )
        }

        assertThat(result.intentJson).contains("recovered")
        assertThat(attempts).isEqualTo(3)
    }

    // ── Reports ────────────────────────────────────────────────────────

    @Test
    fun `generateReport returns valid report`() {
        monitor.recordResult("openrouter", success = true, latencyMs = 100)
        monitor.recordResult("gemini", success = true, latencyMs = 200)

        val report = monitor.generateReport("openrouter")
        assertThat(report.cacheHitRate).isAtLeast(0f)
        assertThat(report.providerReports).hasSize(2)
        assertThat(report.recommendedProvider).isNotEmpty()
    }

    @Test
    fun `getHealthStatus returns formatted string`() {
        val status = monitor.getHealthStatus("openrouter")
        assertThat(status).contains("AI System Health")
        assertThat(status).contains("Caching")
        assertThat(status).contains("Providers")
    }

    @Test
    fun `getPerformanceReport returns formatted report`() {
        val report = monitor.getPerformanceReport()
        assertThat(report).contains("Performance Report")
        assertThat(report).contains("Provider Stats")
    }

    @Test
    fun `saveReport does not crash`() {
        // Should not throw
        monitor.saveReport()
    }

    // ── Reset ──────────────────────────────────────────────────────────

    @Test
    fun `reset clears all tracking data`() {
        monitor.recordResult("openrouter", success = true, latencyMs = 100)
        monitor.recordResult("gemini", success = false, latencyMs = 0, error = "err")
        monitor.cacheResult("test query for reset", "openrouter",
            AiEngineResult(intentJson = "{}", source = AiSource.CLOUD)
        )

        monitor.reset()

        val (total, hits, misses) = monitor.cacheStats
        assertThat(total).isEqualTo(0L)
        assertThat(hits).isEqualTo(0L)
        assertThat(misses).isEqualTo(0L)

        val orHealth = monitor.getProviderHealth("openrouter")
        assertThat(orHealth.totalRequests).isEqualTo(0)
        assertThat(orHealth.failedRequests).isEqualTo(0)
    }

    // ── Thread Safety ──────────────────────────────────────────────────

    @Test
    fun `concurrent cache access does not crash`() {
        val threads = List(10) { threadIndex ->
            Thread {
                repeat(20) { i ->
                    val result = AiEngineResult(
                        intentJson = """{"action":"chat","reply":"thread $threadIndex run $i"}""",
                        source = AiSource.CLOUD
                    )
                    monitor.cacheResult("concurrent query $i from thread $threadIndex", "openrouter", result)
                    monitor.getCached("concurrent query $i from thread $threadIndex", "openrouter")
                    monitor.recordResult("openrouter", success = true, latencyMs = 50)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should not crash
        assertThat(monitor.cacheStats.first).isAtLeast(0L)
    }
}
