package com.interndra.ui.viewmodel

/**
 * ChatTitleGenerator — standalone utility to generate short, meaningful
 * chat titles (2-7 words) from the first user message.
 *
 * Extracted from HybridAgentViewModel so it can be unit-tested without
 * requiring the Android framework (no TTS, Room, Shizuku dependencies).
 */
object ChatTitleGenerator {

    private val stopWords = setOf(
        "the", "and", "for", "that", "this", "with", "was", "are", "can", "how",
        "what", "when", "where", "which", "who", "will", "have", "has", "does",
        "please", "just", "like", "from", "about", "than", "then", "also",
        "very", "much", "some", "any", "each", "every", "only", "other"
    )

    /**
     * Generate a short chat title (2-7 words) from a user message.
     *
     * @param message  The first user message in the conversation
     * @return A clean, capitalized title; "New Chat" if input is empty
     */
    fun generate(message: String): String {
        val cleaned = message
            .replace(Regex("[^a-zA-Z0-9\\s?.,!-]"), "")
            .trim()
        if (cleaned.isEmpty()) return "New Chat"

        val words = cleaned.split(Regex("\\s+"))
            .filter { it.length > 2 }
            .filterNot { it.lowercase() in stopWords }

        if (words.isEmpty()) return cleaned.take(40).trim()

        val titleWords = words.take(7)
        val title = titleWords.joinToString(" ")
            .replaceFirstChar { it.uppercase() }
            .take(50)
            .trim()

        return if (title.split(" ").size >= 2) title
        else cleaned.take(40).trim()
    }
}
