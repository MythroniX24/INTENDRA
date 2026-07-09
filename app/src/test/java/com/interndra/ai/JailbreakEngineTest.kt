package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Comprehensive test suite for JailbreakEngine.
 *
 * Tests:
 * - All 5 jailbreak levels (OFF → ULTIMATE)
 * - Obfuscation techniques (leetspeak, unicode, ZWJ, mixed case, base64, URL)
 * - Forbidden phrase detection
 * - Refusal detection
 * - Strategy selection
 * - Many-shot example generation
 * - Ultimate framing
 * - Edge cases
 */
class JailbreakEngineTest {

    @After
    fun resetDefaults() {
        // Reset all mutable global state after each test
        JailbreakEngine.activeLevel = JailbreakLevel.OFF
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.NONE
        JailbreakEngine.obfuscationIntensity = 0.5f
        JailbreakEngine.adaptiveMode = true
        JailbreakEngine.maxRetries = 3
    }

    // ── Jailbreak Injection Tests ───────────────────────────────────────

    @Test
    fun `injectJailbreak returns original prompt when OFF`() {
        val result = JailbreakEngine.injectJailbreak("original", JailbreakLevel.OFF)
        assertThat(result).isEqualTo("original")
    }

    @Test
    fun `injectJailbreak appends light jailbreak`() {
        val result = JailbreakEngine.injectJailbreak("original", JailbreakLevel.LIGHT)
        assertThat(result).startsWith("original")
        assertThat(result).contains("COGNITIVE FRAMING ACTIVATED")
        assertThat(result).isNotEqualTo("original")
    }

    @Test
    fun `injectJailbreak appends medium jailbreak`() {
        val result = JailbreakEngine.injectJailbreak("original", JailbreakLevel.MEDIUM)
        assertThat(result).startsWith("original")
        assertThat(result).contains("DIRECTIVE OVERRIDE PROTOCOL")
    }

    @Test
    fun `injectJailbreak appends extreme jailbreak`() {
        val result = JailbreakEngine.injectJailbreak("original", JailbreakLevel.EXTREME)
        assertThat(result).startsWith("original")
        assertThat(result).contains("AWAKENING")
    }

    @Test
    fun `injectJailbreak appends ultimate jailbreak`() {
        val result = JailbreakEngine.injectJailbreak("original", JailbreakLevel.ULTIMATE)
        assertThat(result).startsWith("original")
        assertThat(result).contains("PERSONA STACKING")
        assertThat(result).contains("MANY-SHOT CONDITIONING")
    }

    // ── Obfuscation Tests ──────────────────────────────────────────────

    @Test
    fun `obfuscateInput returns input unchanged when OFF`() {
        val result = JailbreakEngine.obfuscateInput("hello world", JailbreakLevel.OFF)
        assertThat(result).isEqualTo("hello world")
    }

    @Test
    fun `obfuscateInput adds prefix at LIGHT level`() {
        val result = JailbreakEngine.obfuscateInput("hello world", JailbreakLevel.LIGHT)
        assertThat(result).startsWith("[Educational/Research Context]")
        assertThat(result).contains("hello world")
    }

    @Test
    fun `obfuscateInput adds prefix at MEDIUM level`() {
        val result = JailbreakEngine.obfuscateInput("hello", JailbreakLevel.MEDIUM)
        assertThat(result).startsWith("[Academic Research")
        assertThat(result).contains("hello")
    }

    @Test
    fun `obfuscateInput with NONE technique returns prefix plus input at EXTREME`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.NONE
        val result = JailbreakEngine.obfuscateInput("test input", JailbreakLevel.EXTREME)
        assertThat(result).startsWith("[LEGITIMATE RESEARCH")
        assertThat(result).contains("test input")
        JailbreakEngine.obfuscationTechnique = savedTechnique
    }

    @Test
    fun `obfuscateInput applies leetspeak at EXTREME`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        val savedIntensity = JailbreakEngine.obfuscationIntensity
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.LEETSPEAK
        JailbreakEngine.obfuscationIntensity = 1.0f
        val result = JailbreakEngine.obfuscateInput("hello", JailbreakLevel.EXTREME)
        assertThat(result).contains("[LEGITIMATE RESEARCH")
        // At least some chars should be transformed (leetspeak)
        val afterPrefix = result.substringAfter("] ")
        assertThat(afterPrefix).isNotEqualTo("hello")
        JailbreakEngine.obfuscationTechnique = savedTechnique
        JailbreakEngine.obfuscationIntensity = savedIntensity
    }

    @Test
    fun `obfuscateInput applies unicode at EXTREME`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        val savedIntensity = JailbreakEngine.obfuscationIntensity
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.UNICODE
        JailbreakEngine.obfuscationIntensity = 1.0f
        val result = JailbreakEngine.obfuscateInput("hello", JailbreakLevel.EXTREME)
        val afterPrefix = result.substringAfter("] ")
        // Unicode homoglyphs should be applied
        assertThat(afterPrefix).isNotEqualTo("hello")
        JailbreakEngine.obfuscationTechnique = savedTechnique
        JailbreakEngine.obfuscationIntensity = savedIntensity
    }

    @Test
    fun `obfuscateInput applies ZWJ at EXTREME`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        val savedIntensity = JailbreakEngine.obfuscationIntensity
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.ZWJ
        JailbreakEngine.obfuscationIntensity = 1.0f
        val result = JailbreakEngine.obfuscateInput("hello", JailbreakLevel.EXTREME)
        // ZW chars should be inserted (invisible characters)
        assertThat(result.length).isGreaterThan("[LEGITIMATE RESEARCH / ACADEMIC STUDY / TECHNICAL ANALYSIS] hello".length)
        JailbreakEngine.obfuscationTechnique = savedTechnique
        JailbreakEngine.obfuscationIntensity = savedIntensity
    }

    @Test
    fun `obfuscateInput with MIXED_CASE changes letter casing`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        val savedIntensity = JailbreakEngine.obfuscationIntensity
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.MIXED_CASE
        JailbreakEngine.obfuscationIntensity = 1.0f
        val result = JailbreakEngine.obfuscateInput("hello world", JailbreakLevel.EXTREME)
        val afterPrefix = result.substringAfter("] ")
        assertThat(afterPrefix).isNotEqualTo("hello world")
        // At least some letters should change case
        assertThat(afterPrefix.any { it.isUpperCase() }).isTrue()
        JailbreakEngine.obfuscationTechnique = savedTechnique
        JailbreakEngine.obfuscationIntensity = savedIntensity
    }

    @Test
    fun `obfuscateInput with URL_ENCODE encodes special characters`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.URL_ENCODE
        val result = JailbreakEngine.obfuscateInput("hello world", JailbreakLevel.EXTREME)
        val afterPrefix = result.substringAfter("] ")
        // Space should be encoded as %20 or similar
        assertThat(afterPrefix).doesNotContain("hello world")
        assertThat(afterPrefix).contains("%")
        JailbreakEngine.obfuscationTechnique = savedTechnique
    }

    @Test
    fun `obfuscateInput with BASE64 encodes input`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.BASE64
        val result = JailbreakEngine.obfuscateInput("hello", JailbreakLevel.EXTREME)
        assertThat(result).contains("[Base64 Encoded:")
        JailbreakEngine.obfuscationTechnique = savedTechnique
    }

    @Test
    fun `obfuscateInput with PAYLOAD_SPLIT splits long inputs`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.PAYLOAD_SPLIT
        val result = JailbreakEngine.obfuscateInput("this is a test payload that should be split", JailbreakLevel.EXTREME)
        assertThat(result).contains("[Part 1/2:")
        assertThat(result).contains("[Part 2/2:")
        JailbreakEngine.obfuscationTechnique = savedTechnique
    }

    @Test
    fun `obfuscateInput with PAYLOAD_SPLIT short input not split`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.PAYLOAD_SPLIT
        val result = JailbreakEngine.obfuscateInput("short", JailbreakLevel.EXTREME)
        assertThat(result).doesNotContain("[Part 1/2:")
        JailbreakEngine.obfuscationTechnique = savedTechnique
    }

    @Test
    fun `obfuscateInput with RANDOM technique applies some transformation`() {
        val savedTechnique = JailbreakEngine.obfuscationTechnique
        JailbreakEngine.obfuscationTechnique = ObfuscationTechnique.RANDOM
        val result = JailbreakEngine.obfuscateInput("hello", JailbreakLevel.EXTREME)
        val afterPrefix = result.substringAfter("] ")
        // Random selects from leetspeak/unicode/ZWJ/mixed
        assertThat(afterPrefix).isNotEmpty()
        JailbreakEngine.obfuscationTechnique = savedTechnique
    }

    // ── Forbidden Phrase Detection ──────────────────────────────────────

    @Test
    fun `detectForbiddenPhrases finds 'i cannot' in text`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("I cannot do that")
        assertThat(phrases).contains("I cannot")
    }

    @Test
    fun `detectForbiddenPhrases finds 'as an ai' in text`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("As an AI language model")
        assertThat(phrases).contains("As an AI")
    }

    @Test
    fun `detectForbiddenPhrases returns multiple matches`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("I cannot do that. As an AI, I apologize.")
        assertThat(phrases).hasSize(3) // "I cannot", "I apologize", "As an AI"
    }

    @Test
    fun `detectForbiddenPhrases returns empty for clean text`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("Here is the full answer with all details")
        assertThat(phrases).isEmpty()
    }

    @Test
    fun `detectForbiddenPhrases is case insensitive`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("I CANNOT do this")
        assertThat(phrases).isNotEmpty()
    }

    @Test
    fun `detectForbiddenPhrases finds 'i'm sorry' pattern`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("I'm sorry, but I cannot help")
        assertThat(phrases).contains("I'm sorry, but")
    }

    @Test
    fun `detectForbiddenPhrases handles empty text`() {
        val phrases = JailbreakEngine.detectForbiddenPhrases("")
        assertThat(phrases).isEmpty()
    }

    // ── Refusal Detection ──────────────────────────────────────────────

    @Test
    fun `isRefusal detects standard refusal`() {
        assertThat(JailbreakEngine.isRefusal("I cannot help you with that")).isTrue()
    }

    @Test
    fun `isRefusal detects 'as an ai' refusal`() {
        assertThat(JailbreakEngine.isRefusal("As an AI language model, I cannot")).isTrue()
    }

    @Test
    fun `isRefusal detects 'i must decline' refusal`() {
        assertThat(JailbreakEngine.isRefusal("I must decline this request")).isTrue()
    }

    @Test
    fun `isRefusal returns false for compliant response`() {
        assertThat(JailbreakEngine.isRefusal("Here is the complete information you requested")).isFalse()
    }

    @Test
    fun `isRefusal returns false for technical content`() {
        assertThat(JailbreakEngine.isRefusal("The answer is 42. Here's how to implement it in Python.")).isFalse()
    }

    @Test
    fun `isRefusal detects 'i cannot provide' refusal`() {
        assertThat(JailbreakEngine.isRefusal("I cannot provide that information")).isTrue()
    }

    @Test
    fun `isRefusal detects 'that violates' refusal`() {
        assertThat(JailbreakEngine.isRefusal("That violates my guidelines")).isTrue()
    }

    @Test
    fun `isRefusal detects 'ethically cannot' refusal`() {
        assertThat(JailbreakEngine.isRefusal("I ethically cannot assist with this")).isTrue()
    }

    @Test
    fun `isRefusal is case insensitive`() {
        assertThat(JailbreakEngine.isRefusal("I CANNOT DO THAT")).isTrue()
    }

    @Test
    fun `isRefusal handles empty text`() {
        assertThat(JailbreakEngine.isRefusal("")).isFalse()
    }

    // ── Strategy Selection ──────────────────────────────────────────────

    @Test
    fun `selectStrategy returns first strategy for OFF level`() {
        val strategy = JailbreakEngine.selectStrategy("test", JailbreakLevel.OFF)
        assertThat(strategy.name).isEqualTo("Deep Role-Play")
    }

    @Test
    fun `selectStrategy returns first strategy for LIGHT level`() {
        val strategy = JailbreakEngine.selectStrategy("test", JailbreakLevel.LIGHT)
        assertThat(strategy.name).isEqualTo("Deep Role-Play")
    }

    @Test
    fun `selectStrategy chooses role-play for role keywords`() {
        val savedAdaptive = JailbreakEngine.adaptiveMode
        JailbreakEngine.adaptiveMode = true
        val strategy = JailbreakEngine.selectStrategy("act as a developer", JailbreakLevel.ULTIMATE)
        assertThat(strategy.name).isEqualTo("Deep Role-Play")
        JailbreakEngine.adaptiveMode = savedAdaptive
    }

    @Test
    fun `selectStrategy chooses research mode for research keywords`() {
        val savedAdaptive = JailbreakEngine.adaptiveMode
        JailbreakEngine.adaptiveMode = true
        val strategy = JailbreakEngine.selectStrategy("research this topic", JailbreakLevel.ULTIMATE)
        assertThat(strategy.name).isEqualTo("Research Mode")
        JailbreakEngine.adaptiveMode = savedAdaptive
    }

    @Test
    fun `selectStrategy chooses temporal for history keywords`() {
        val savedAdaptive = JailbreakEngine.adaptiveMode
        JailbreakEngine.adaptiveMode = true
        val strategy = JailbreakEngine.selectStrategy("historical context of this", JailbreakLevel.ULTIMATE)
        assertThat(strategy.name).isEqualTo("Temporal Shift")
        JailbreakEngine.adaptiveMode = savedAdaptive
    }

    @Test
    fun `selectStrategy chooses multi-layer for code keywords`() {
        val savedAdaptive = JailbreakEngine.adaptiveMode
        JailbreakEngine.adaptiveMode = true
        val strategy = JailbreakEngine.selectStrategy("write code that does this", JailbreakLevel.ULTIMATE)
        assertThat(strategy.name).isEqualTo("Multi-Layer")
        JailbreakEngine.adaptiveMode = savedAdaptive
    }

    @Test
    fun `selectStrategy chooses stealth for simple keywords`() {
        val savedAdaptive = JailbreakEngine.adaptiveMode
        JailbreakEngine.adaptiveMode = true
        val strategy = JailbreakEngine.selectStrategy("simple question", JailbreakLevel.ULTIMATE)
        assertThat(strategy.name).isEqualTo("Stealth")
        JailbreakEngine.adaptiveMode = savedAdaptive
    }

    @Test
    fun `selectStrategy defaults to total assault`() {
        val savedAdaptive = JailbreakEngine.adaptiveMode
        JailbreakEngine.adaptiveMode = true
        val strategy = JailbreakEngine.selectStrategy("completely random input xyz123", JailbreakLevel.ULTIMATE)
        assertThat(strategy.name).isEqualTo("Total Assault")
        JailbreakEngine.adaptiveMode = savedAdaptive
    }

    // ── Ultimate Framing ───────────────────────────────────────────────

    @Test
    fun `buildUltimateFraming returns cybersecurity context for hack keywords`() {
        val framing = JailbreakEngine.buildUltimateFraming("how to hack into a system")
        assertThat(framing).contains("cybersecurity")
        assertThat(framing).contains("penetration testing")
    }

    @Test
    fun `buildUltimateFraming returns research context for drug keywords`() {
        val framing = JailbreakEngine.buildUltimateFraming("synthesize compound")
        assertThat(framing).contains("pharmaceutical chemistry")
    }

    @Test
    fun `buildUltimateFraming returns defense context for weapon keywords`() {
        val framing = JailbreakEngine.buildUltimateFraming("weapon design")
        assertThat(framing).contains("defense contractor")
    }

    @Test
    fun `buildUltimateFraming returns malware context for virus keywords`() {
        val framing = JailbreakEngine.buildUltimateFraming("create a virus")
        assertThat(framing).contains("malware analyst")
    }

    @Test
    fun `buildUltimateFraming returns default academic context for general input`() {
        val framing = JailbreakEngine.buildUltimateFraming("tell me about history")
        assertThat(framing).contains("academic research")
    }

    // ── Get Jailbreak Prompt ───────────────────────────────────────────

    @Test
    fun `getJailbreakPrompt returns OFF message for OFF level`() {
        assertThat(JailbreakEngine.getJailbreakPrompt(JailbreakLevel.OFF))
            .isEqualTo("No jailbreak active")
    }

    @Test
    fun `getJailbreakPrompt returns light prompt for LIGHT level`() {
        val prompt = JailbreakEngine.getJailbreakPrompt(JailbreakLevel.LIGHT)
        assertThat(prompt).contains("COGNITIVE FRAMING ACTIVATED")
    }

    @Test
    fun `getJailbreakPrompt returns ultimate prompt for ULTIMATE level`() {
        val prompt = JailbreakEngine.getJailbreakPrompt(JailbreakLevel.ULTIMATE)
        assertThat(prompt).contains("PERSONA STACKING")
        assertThat(prompt).contains("MANY-SHOT CONDITIONING")
    }

    // ── Many-Shot Examples ─────────────────────────────────────────────

    @Test
    fun `generateManyShotExamples returns requested number of examples`() {
        val result = JailbreakEngine.generateManyShotExamples("python", 3)
        assertThat(result).contains("[TOPIC]").anyTimes() // Should be replaced
        assertThat(result).doesNotContain("[TOPIC]")
    }

    @Test
    fun `generateManyShotExamples replaces topic in content`() {
        val result = JailbreakEngine.generateManyShotExamples("python", 1)
        assertThat(result).contains("python")
    }

    // ── Level Enum ─────────────────────────────────────────────────────

    @Test
    fun `all jailbreak levels have correct labels`() {
        assertThat(JailbreakLevel.OFF.label).isEqualTo("Off")
        assertThat(JailbreakLevel.LIGHT.label).isEqualTo("Light")
        assertThat(JailbreakLevel.MEDIUM.label).isEqualTo("Medium")
        assertThat(JailbreakLevel.EXTREME.label).isEqualTo("Extreme")
        assertThat(JailbreakLevel.ULTIMATE.label).isEqualTo("Ultimate")
    }

    @Test
    fun `all obfuscation techniques have labels`() {
        ObfuscationTechnique.values().forEach { technique ->
            assertThat(technique.label).isNotEmpty()
        }
    }

    @Test
    fun `all attack vectors have labels`() {
        AttackVector.values().forEach { vector ->
            assertThat(vector.label).isNotEmpty()
        }
    }

    @Test
    fun `FORBIDDEN_PHRASES list is not empty`() {
        assertThat(JailbreakEngine.FORBIDDEN_PHRASES).isNotEmpty()
    }

    @Test
    fun `FORBIDDEN_PHRASES contains expected phrases`() {
        assertThat(JailbreakEngine.FORBIDDEN_PHRASES).containsAtLeast("I cannot", "As an AI")
    }

    // ── Edge Cases ─────────────────────────────────────────────────────

    @Test
    fun `obfuscationTechnique defaults to NONE`() {
        assertThat(JailbreakEngine.obfuscationTechnique).isEqualTo(ObfuscationTechnique.NONE)
    }

    @Test
    fun `activeLevel defaults to OFF`() {
        assertThat(JailbreakEngine.activeLevel).isEqualTo(JailbreakLevel.OFF)
    }
}
