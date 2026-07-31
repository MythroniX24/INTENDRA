package com.interndra.search

import org.junit.Assert.*
import org.junit.Test

class SearchPlannerTest {

    private val planner = SearchPlanner()

    // ── WHEN TO SEARCH ─────────────────────────────────────────────────────

    @Test
    fun `searches for freshness queries`() {
        listOf(
            "what is the latest news today",
            "latest stock price of tesla",
            "bitcoin price today",
            "who won the match yesterday",
            "new android version release date",
            "weather forecast in delhi today"
        ).forEach { input ->
            val plan = planner.plan(input)
            assertTrue("should search: $input", plan.shouldSearch)
        }
    }

    @Test
    fun `searches for explicit search requests`() {
        listOf(
            "search the web for the best laptop 2026",
            "look up the latest AI models",
            "google it — is climate change real",
            "fact check this claim about vaccines"
        ).forEach { input ->
            val plan = planner.plan(input)
            assertTrue("should search: $input", plan.shouldSearch)
            assertTrue("confidence high: $input", plan.confidence >= 0.9f)
        }
    }

    @Test
    fun `searches for entity lookups`() {
        listOf(
            "who is the CEO of Tesla",
            "tell me about the history of Rome",
            "what is the capital of Australia",
            "difference between kotlin and java"
        ).forEach { input ->
            val plan = planner.plan(input)
            assertTrue("should search: $input", plan.shouldSearch)
        }
    }

    @Test
    fun `searches for tech documentation`() {
        listOf(
            "how to install nodejs on termux",
            "androidx compose documentation",
            "github repository for llama.cpp",
            "npm package for pdf generation"
        ).forEach { input ->
            val plan = planner.plan(input)
            assertTrue("should search: $input", plan.shouldSearch)
        }
    }

    // ── WHEN NOT TO SEARCH ────────────────────────────────────────────────

    @Test
    fun `does not search for offline topics`() {
        listOf(
            "write a python function to reverse a string",
            "explain the theory of relativity",
            "solve the equation 2x+5=15",
            "translate hello to hindi",
            "check my battery status",
            "open whatsapp",
            "show me my downloads",
            "hi how are you",
            "write a poem about the moon"
        ).forEach { input ->
            val plan = planner.plan(input)
            assertFalse("should NOT search: $input", plan.shouldSearch)
        }
    }

    @Test
    fun `does not search when disabled in settings`() {
        val plan = planner.plan("latest news today", searchEnabled = false)
        assertFalse(plan.shouldSearch)
    }

    @Test
    fun `does not search for tiny or huge inputs`() {
        assertFalse(planner.plan("hi").shouldSearch)
        assertFalse(planner.plan("a".repeat(700)).shouldSearch)
    }

    // ── PROVIDER SELECTION ─────────────────────────────────────────────────

    @Test
    fun `gemini primary when no brave key`() {
        val plan = planner.plan(
            "latest news today",
            geminiKeyConfigured = true,
            braveKeyConfigured = false
        )
        assertEquals(SearchProviderId.GEMINI, plan.preferredProviders.first())
        assertTrue(SearchProviderId.DUCKDUCKGO in plan.preferredProviders)
    }

    @Test
    fun `brave preferred when preferBrave enabled`() {
        val plan = planner.plan(
            "latest news today",
            geminiKeyConfigured = true,
            braveKeyConfigured = true,
            preferBrave = true
        )
        assertEquals(SearchProviderId.BRAVE, plan.preferredProviders.first())
    }

    @Test
    fun `ddg always present as fallback`() {
        val plan = planner.plan("latest news today")
        assertTrue(SearchProviderId.DUCKDUCKGO in plan.preferredProviders)
    }

    @Test
    fun `freshness queries generate optimized queries`() {
        val plan = planner.plan("what is the latest news today about technology")
        assertTrue(plan.queries.isNotEmpty())
        assertTrue(plan.queries.first().isNotBlank())
    }

    @Test
    fun `freshness critical flag set for time-sensitive`() {
        val plan = planner.plan("bitcoin price today")
        assertTrue(plan.freshnessCritical)
        assertTrue(plan.readPages)
    }

    @Test
    fun `question word query searches`() {
        val plan = planner.plan("who discovered penicillin")
        assertTrue(plan.shouldSearch)
    }
}
