package com.interndra.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

/**
 * ChatTitleGeneratorTest — tests for standalone chat title generation.
 * No ViewModel or Android framework needed — pure logic tests.
 */
class ChatTitleGeneratorTest {

    @Test
    fun `generates short title from long message`() {
        val title = ChatTitleGenerator.generate(
            "Can you help me check my battery status and storage space?"
        )
        assertTrue(title.length <= 50)
        val words = title.split(" ")
        assertTrue("Title should have 2-7 words: '$title'", words.size in 2..7)
    }

    @Test
    fun `removes stop words`() {
        val title = ChatTitleGenerator.generate("what is the best way to do this")
        assertFalse(title.lowercase().startsWith("the "))
        assertFalse(title.lowercase().startsWith("what"))
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `handles very short message`() {
        val title = ChatTitleGenerator.generate("Hello")
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `returns New Chat for empty input`() {
        assertEquals("New Chat", ChatTitleGenerator.generate(""))
    }

    @Test
    fun `strips special characters`() {
        val title = ChatTitleGenerator.generate("!!! ??? Test @#$ message ^&*")
        assertEquals("Test message", title)
    }

    @Test
    fun `capitalizes first letter`() {
        val title = ChatTitleGenerator.generate("show me the weather forecast")
        assertTrue("Expected capitalized: '$title'", title[0].isUpperCase())
    }

    @Test
    fun `handles very long input`() {
        val longMsg = "A".repeat(500) + " battery status check"
        val title = ChatTitleGenerator.generate(longMsg)
        assertTrue(title.length <= 50)
    }

    @Test
    fun `strips question marks`() {
        val title = ChatTitleGenerator.generate("What is the capital of France?")
        assertTrue(title.isNotBlank())
        assertFalse(title.contains("?"))
    }

    @Test
    fun `handles whitespace only`() {
        val title = ChatTitleGenerator.generate("   ")
        assertEquals("New Chat", title)
    }

    @Test
    fun `handles punctuation only`() {
        val title = ChatTitleGenerator.generate("???!!!...")
        assertEquals("New Chat", title)
    }

    @Test
    fun `generates title for code-related query`() {
        val title = ChatTitleGenerator.generate("How do I fix NullPointerException in Kotlin?")
        assertTrue(title.isNotBlank())
        assertTrue(title.length <= 50)
    }

    @Test
    fun `generates title for device query`() {
        val title = ChatTitleGenerator.generate("What is my battery percentage right now?")
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `handles single meaningful word`() {
        val title = ChatTitleGenerator.generate("Hello world testing")
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `title never exceeds 50 characters`() {
        val longMsg = "A very long message that goes on ".repeat(20)
        val title = ChatTitleGenerator.generate(longMsg)
        assertTrue(title.length <= 50)
    }

    @Test
    fun `title has at least 2 characters when not New Chat`() {
        val title = ChatTitleGenerator.generate("This is a test message")
        assertTrue(title.length >= 2 || title == "New Chat")
    }
}
