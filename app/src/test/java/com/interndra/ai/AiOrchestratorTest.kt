package com.interndra.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import com.google.common.truth.Truth.assertThat
import com.interndra.data.model.*
import com.interndra.util.Constants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for AiOrchestrator.
 *
 * Tests:
 * - Routing decisions (LOCAL, CLOUD, LOCAL_WITH_CLOUD_FALLBACK) for all privacy modes
 * - Cloud AI execution for both OpenRouter and Gemini providers
 * - Fallback chains (Gemini → OpenRouter → Local → Error)
 * - JSON response parsing
 * - Internet connectivity checks
 * - Jailbreak integration
 * - Edge cases
 */
class AiOrchestratorTest {

    private lateinit var mockContext: Context
    private lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var mockLocalEngine: LocalAiEngine
    private lateinit var mockCloudEngine: CloudAiEngine
    private lateinit var mockGeminiEngine: GeminiAiEngine
    private lateinit var mockNetwork: Network
    private lateinit var mockCapabilities: NetworkCapabilities
    private lateinit var orchestrator: AiOrchestrator

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockConnectivityManager = mockk(relaxed = true)
        mockLocalEngine = mockk(relaxed = true)
        mockCloudEngine = mockk(relaxed = true)
        mockGeminiEngine = mockk(relaxed = true)
        mockNetwork = mockk(relaxed = true)
        mockCapabilities = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true

        orchestrator = AiOrchestrator(
            context = mockContext,
            localEngine = mockLocalEngine,
            cloudEngine = mockCloudEngine,
            geminiEngine = mockGeminiEngine
        )

        // Configure cloud engine as configured
        every { mockCloudEngine.isConfigured() } returns true
        every { mockGeminiEngine.isConfigured() } returns true
    }

    // ── Routing: LOCAL_ONLY Mode ───────────────────────────────────────

    @Test
    fun `LOCAL_ONLY mode always routes to local`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"local answer"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "hello world", emptyList(), PrivacyMode.LOCAL_ONLY
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.LOCAL)
        assertThat(result.engineResult.source).isEqualTo(AiSource.LOCAL)
        assertThat(result.intent.action).isEqualTo("chat")
    }

    // ── Routing: CLOUD_ENHANCED Mode ───────────────────────────────────

    @Test
    fun `CLOUD_ENHANCED mode routes to cloud when internet available`() = runBlocking {
        coEvery { mockCloudEngine.parseIntent(any(), any(), any(), any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"cloud answer"}""",
            source = AiSource.CLOUD
        )

        val result = orchestrator.process(
            "cloud query", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.CLOUD)
        assertThat(result.engineResult.source).isEqualTo(AiSource.CLOUD)
    }

    @Test
    fun `CLOUD_ENHANCED mode falls back to local when no internet`() = runBlocking {
        every { mockConnectivityManager.activeNetwork } returns null

        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"local fallback"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "no internet query", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.LOCAL)
    }

    // ── Routing: HYBRID Mode ──────────────────────────────────────────

    @Test
    fun `HYBRID mode routes short local keywords to local`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"hi"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "hi", emptyList(), PrivacyMode.HYBRID
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.LOCAL)
    }

    @Test
    fun `HYBRID mode routes long cloud keywords to cloud fallback`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"unknown","commands":[]}""",
            source = AiSource.LOCAL
        )
        coEvery { mockCloudEngine.parseIntent(any(), any(), any(), any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"analyze","reply":"analysis result"}""",
            source = AiSource.CLOUD
        )

        val result = orchestrator.process(
            "analyze this complex codebase and tell me what design patterns are being used",
            emptyList(), PrivacyMode.HYBRID
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK)
    }

    @Test
    fun `HYBRID mode routes long inputs to cloud fallback`() = runBlocking {
        val longInput = "a".repeat(250)

        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"unknown","commands":[]}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            longInput, emptyList(), PrivacyMode.HYBRID
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK)
    }

    // ── Cloud Execution ────────────────────────────────────────────────

    @Test
    fun `cloud execution with OpenRouter succeeds`() = runBlocking {
        orchestrator.activeProvider = Constants.AiProvider.OPENROUTER
        every { mockCloudEngine.isConfigured() } returns true
        coEvery { mockCloudEngine.parseIntent(any(), any(), any(), any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"openrouter response"}""",
            source = AiSource.CLOUD,
            modelUsed = "openrouter/auto"
        )

        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"local"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "complex analysis task", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        assertThat(result.engineResult.source).isEqualTo(AiSource.CLOUD)
    }

    @Test
    fun `cloud execution with Gemini succeeds`() = runBlocking {
        orchestrator.activeProvider = Constants.AiProvider.GEMINI
        every { mockGeminiEngine.isConfigured() } returns true
        coEvery { mockGeminiEngine.parseIntent(any(), any(), any(), any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"gemini response"}""",
            source = AiSource.CLOUD,
            modelUsed = "gemini/gemini-2.5-flash"
        )

        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"local"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "gemini task", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        assertThat(result.engineResult.source).isEqualTo(AiSource.CLOUD)
    }

    @Test
    fun `Gemini falls back to OpenRouter on error`() = runBlocking {
        orchestrator.activeProvider = Constants.AiProvider.GEMINI
        every { mockGeminiEngine.isConfigured() } returns true
        coEvery { mockGeminiEngine.parseIntent(any(), any(), any(), any(), any()) } throws
            IllegalStateException("Gemini API error")
        coEvery { mockCloudEngine.parseIntent(any(), any(), any(), any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"openrouter fallback"}""",
            source = AiSource.CLOUD
        )

        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"local"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "gemini failing task", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        // Should fallback to OpenRouter
        assertThat(result.engineResult.source).isEqualTo(AiSource.FALLBACK)
    }

    @Test
    fun `Gemini falls back to OpenRouter when not configured`() = runBlocking {
        orchestrator.activeProvider = Constants.AiProvider.GEMINI
        every { mockGeminiEngine.isConfigured() } returns false
        coEvery { mockCloudEngine.parseIntent(any(), any(), any(), any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"openrouter"}""",
            source = AiSource.CLOUD
        )

        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"local"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "unconfigured gemini", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        assertThat(result.engineResult.source).isEqualTo(AiSource.FALLBACK)
    }

    // ── Local Execution ────────────────────────────────────────────────

    @Test
    fun `local engine errors are caught gracefully`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } throws
            IllegalStateException("Local engine crashed")

        val result = orchestrator.process(
            "hello", emptyList(), PrivacyMode.LOCAL_ONLY
        )

        assertThat(result.engineResult.isSuccess).isTrue()
        assertThat(result.engineResult.intentJson).contains("unknown")
    }

    // ── JSON Parsing ───────────────────────────────────────────────────

    @Test
    fun `parses valid JSON response correctly`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"battery_info","reply":"Checking...","commands":[{"type":"ADB_SHELL","command":"dumpsys battery","description":"Battery status"}]}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "battery check", emptyList(), PrivacyMode.LOCAL_ONLY
        )

        assertThat(result.intent.action).isEqualTo("battery_info")
        assertThat(result.intent.reply).isEqualTo("Checking...")
        assertThat(result.intent.commands).hasSize(1)
        assertThat(result.intent.commands[0].command).isEqualTo("dumpsys battery")
    }

    @Test
    fun `parses JSON with action unknown gracefully`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"unknown","commands":[]}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "something unknown", emptyList(), PrivacyMode.LOCAL_ONLY
        )

        assertThat(result.intent.action).isEqualTo("unknown")
        assertThat(result.intent.commands).isEmpty()
    }

    // ── Jailbreak Integration ──────────────────────────────────────────

    @Test
    fun `jailbreak flag is passed to cloud engine`() = runBlocking {
        orchestrator.jailbreakActive = true
        orchestrator.jailbreakLevel = JailbreakLevel.LIGHT

        coEvery { mockCloudEngine.parseIntent(any(), any(), any(), true, JailbreakLevel.LIGHT) } returns
            AiEngineResult(intentJson = """{"action":"chat","reply":"jailbroken"}""", source = AiSource.CLOUD)
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns
            AiEngineResult(intentJson = """{"action":"chat","reply":"local"}""", source = AiSource.LOCAL)

        val result = orchestrator.process(
            "complex analysis task for research", emptyList(), PrivacyMode.CLOUD_ENHANCED
        )

        assertThat(result.engineResult.source).isEqualTo(AiSource.CLOUD)
    }

    // ── Explanation ────────────────────────────────────────────────────

    @Test
    fun `buildExplanation includes source and latency`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"chat","reply":"test"}""",
            source = AiSource.LOCAL,
            modelUsed = "local-model",
            latencyMs = 150
        )

        val result = orchestrator.process(
            "test", emptyList(), PrivacyMode.LOCAL_ONLY
        )

        assertThat(result.explanation).contains("Local AI")
        assertThat(result.explanation).contains("150ms")
    }

    // ── Routing Decisions ──────────────────────────────────────────────

    @Test
    fun `opens and launches are routed locally`() = runBlocking {
        coEvery { mockLocalEngine.parseIntent(any(), any()) } returns AiEngineResult(
            intentJson = """{"action":"open_app","reply":"Opening"}""",
            source = AiSource.LOCAL
        )

        val result = orchestrator.process(
            "open WhatsApp", emptyList(), PrivacyMode.HYBRID
        )

        assertThat(result.routingDecision).isEqualTo(RoutingDecision.LOCAL)
    }
}
