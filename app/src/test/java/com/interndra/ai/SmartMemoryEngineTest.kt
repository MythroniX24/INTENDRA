package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import com.interndra.data.model.SmartMemoryEntity
import com.interndra.data.model.SmartMemoryPolicy
import com.interndra.data.model.SmartMemoryScope
import com.interndra.data.model.SmartMemoryType
import org.junit.Test

class SmartMemoryEngineTest {
    private val engine = SmartMemoryEngine()

    private fun memory(
        id: Long,
        type: SmartMemoryType,
        summary: String,
        chatId: Long? = null,
        projectId: Long? = null,
        importance: Int = 5
    ) = SmartMemoryEntity(
        id = id,
        type = type.name,
        title = summary.take(30),
        summary = summary,
        keywords = summary,
        chatId = chatId,
        projectId = projectId,
        importanceScore = importance
    )

    @Test
    fun simpleQuestion_skipsRetrieval() {
        val result = engine.retrieve(
            query = "What is 2 + 2?",
            candidates = listOf(memory(1, SmartMemoryType.USER, "User prefers Kotlin")),
            scope = SmartMemoryScope(chatId = 1),
            maxTokens = 400
        )

        assertThat(result.records).isEmpty()
        assertThat(result.estimatedTokens).isEqualTo(0)
        assertThat(result.decision.shouldRetrieve).isFalse()
    }

    @Test
    fun explicitRecall_retrievesOnlyMatchingScopedMemories() {
        val result = engine.retrieve(
            query = "Continue our INTENDRA project discussion from before",
            candidates = listOf(
                memory(1, SmartMemoryType.CHAT, "INTENDRA uses Kotlin and llama.cpp", chatId = 10),
                memory(2, SmartMemoryType.CHAT, "Physics notes", chatId = 11),
                memory(3, SmartMemoryType.PROJECT, "INTENDRA Android architecture", projectId = 7),
                memory(4, SmartMemoryType.USER, "User prefers concise explanations")
            ),
            scope = SmartMemoryScope(chatId = 10, projectId = 7),
            maxTokens = 400
        )

        assertThat(result.decision.shouldRetrieve).isTrue()
        assertThat(result.records.map { it.id.toInt() }).containsExactly(1, 3, 4)
        assertThat(result.records.map { it.id.toInt() }).doesNotContain(2)
    }

    @Test
    fun chatMemoryNeverCrossesChatBoundary() {
        val result = engine.retrieve(
            query = "Do you remember the previous physics discussion?",
            candidates = listOf(
                memory(1, SmartMemoryType.CHAT, "Physics formulas and study plan", chatId = 1, importance = 10),
                memory(2, SmartMemoryType.CHAT, "Physics formulas and study plan", chatId = 2, importance = 10)
            ),
            scope = SmartMemoryScope(chatId = 1),
            maxTokens = 400
        )

        assertThat(result.records.map { it.id.toInt() }).containsExactly(1)
    }

    @Test
    fun policyCanDisableEachMemoryLayer() {
        val result = engine.retrieve(
            query = "Continue the project discussion from before",
            candidates = listOf(
                memory(1, SmartMemoryType.USER, "User prefers Kotlin"),
                memory(2, SmartMemoryType.PROJECT, "INTENDRA project uses Room", projectId = 5),
                memory(3, SmartMemoryType.CHAT, "INTENDRA chat decision", chatId = 8)
            ),
            scope = SmartMemoryScope(chatId = 8, projectId = 5),
            maxTokens = 400,
            policy = SmartMemoryPolicy(projectEnabled = false, chatEnabled = false)
        )

        assertThat(result.records.map { it.id.toInt() }).containsExactly(1)
    }

    @Test
    fun retrievalNeverExceedsTokenBudget() {
        val candidates = (1L..20L).map { id ->
            memory(id, SmartMemoryType.USER, "Kotlin project preference detail number $id", importance = 10)
        }
        val result = engine.retrieve(
            query = "What do you remember about my Kotlin project preferences?",
            candidates = candidates,
            scope = SmartMemoryScope(chatId = 1),
            maxTokens = 40
        )

        assertThat(result.estimatedTokens).isAtMost(40)
    }

    @Test
    fun externalCompressionRedactsSecretsAndBoundsLength() {
        val compressed = SmartMemoryEngine.compressForExternal(
            "api_key=super-secret-value user@example.com " + "x".repeat(500),
            maxChars = 80
        )

        assertThat(compressed).doesNotContain("super-secret-value")
        assertThat(compressed).doesNotContain("user@example.com")
        assertThat(compressed.length).isAtMost(81)
    }

    @Test
    fun localCompressionPreservesUserOwnedText() {
        val compressed = SmartMemoryEngine.compress("remember api_key=super-secret-value")

        assertThat(compressed).contains("super-secret-value")
    }

    @Test
    fun ordinaryOneOffCommandIsNotPersisted() {
        assertThat(SmartMemoryEngine.shouldPersistCandidate("list files in Downloads")).isFalse()
        assertThat(SmartMemoryEngine.shouldPersistCandidate("Remember that INTENDRA uses Kotlin")).isTrue()
    }

    @Test
    fun localEmbeddingIsDeterministicAndBounded() {
        val first = SmartMemoryEngine.embeddingJson("INTENDRA Kotlin Room")
        assertThat(first).isEqualTo(SmartMemoryEngine.embeddingJson("INTENDRA Kotlin Room"))
        assertThat(first.split(',')).hasSize(32)
    }

    @Test
    fun duplicateDetectionUsesTokenSimilarity() {
        val existing = memory(1, SmartMemoryType.PROJECT, "INTENDRA uses Kotlin and Room database")

        assertThat(engine.isDuplicate("INTENDRA uses Kotlin and Room database", existing)).isTrue()
        assertThat(engine.isDuplicate("The vacation itinerary uses trains in Europe", existing)).isFalse()
    }
}
