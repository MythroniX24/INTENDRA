package com.interndra.agent

import com.interndra.agent.ComplexityClassifier.Complexity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the agent complexity classifier (SIMPLE / MODERATE / COMPLEX). */
class ComplexityClassifierTest {

    // ── SIMPLE ────────────────────────────────────────────────────────────

    @Test
    fun `simple arithmetic is SIMPLE`() {
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("2 + 2"))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("what is 15% of 300"))
    }

    @Test
    fun `casual conversation is SIMPLE`() {
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("hi"))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("hello there"))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("thanks!"))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("ok"))
    }

    @Test
    fun `short factual question is SIMPLE`() {
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("What is Kotlin?"))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("Translate hello to spanish"))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("What does this word mean"))
    }

    @Test
    fun `empty input is SIMPLE`() {
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify(""))
        assertEquals(Complexity.SIMPLE, ComplexityClassifier.classify("   "))
    }

    // ── MODERATE ──────────────────────────────────────────────────────────

    @Test
    fun `explain requests are MODERATE`() {
        assertEquals(Complexity.MODERATE, ComplexityClassifier.classify("Explain this code"))
        assertEquals(Complexity.MODERATE, ComplexityClassifier.classify("How does this work"))
        assertEquals(Complexity.MODERATE, ComplexityClassifier.classify("Summarize the main points"))
    }

    @Test
    fun `compare requests are MODERATE`() {
        assertEquals(Complexity.MODERATE, ComplexityClassifier.classify("Compare two libraries"))
    }

    @Test
    fun `run a single command is MODERATE`() {
        assertEquals(Complexity.MODERATE, ComplexityClassifier.classify("Run the tests"))
        assertEquals(Complexity.MODERATE, ComplexityClassifier.classify("Show me the battery status"))
    }

    // ── COMPLEX ───────────────────────────────────────────────────────────

    @Test
    fun `fix build is COMPLEX`() {
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Fix the build error"))
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Why is my Gradle build failing"))
    }

    @Test
    fun `build feature is COMPLEX`() {
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Build a chat feature"))
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Create an automation workflow"))
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Add a feature to the app"))
    }

    @Test
    fun `research is COMPLEX`() {
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Research the best library for this"))
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Research the best AI models"))
    }

    @Test
    fun `multi-sentence task with action verb is COMPLEX`() {
        assertEquals(
            Complexity.COMPLEX,
            ComplexityClassifier.classify("Fix the build error. Then run the tests and verify everything works.")
        )
    }

    @Test
    fun `debug a complicated error is COMPLEX`() {
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Debug why the app crashes on startup"))
        assertEquals(Complexity.COMPLEX, ComplexityClassifier.classify("Investigate the memory leak"))
    }
}
