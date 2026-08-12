package com.interndra.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for streaming-safe markdown parsing ([EnhancedMarkdownParser.parse]).
 *
 * While the AI is generating, the text fed to the renderer is a growing,
 * possibly incomplete fragment. The parser must render those fragments
 * progressively without deadlocking (a hang would freeze the whole chat UI)
 * and without flashing empty boxes for just-opened constructs.
 */
class StreamingMarkdownParserTest {

    @Test
    fun `unclosed code fence renders as progressive code block while streaming`() {
        val md = "Here is the code:\n```kotlin\nfun main() {\n    println(\"hel"
        val blocks = EnhancedMarkdownParser.parse(md, isStreaming = true)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is EnhancedBlock.Paragraph)
        val code = blocks[1] as EnhancedBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main()"))
    }

    @Test
    fun `just-opened fence stays plain text while streaming`() {
        val blocks = EnhancedMarkdownParser.parse("Use this:\n```", isStreaming = true)
        assertTrue(blocks.last() is EnhancedBlock.Paragraph)
    }

    @Test
    fun `completed fence still renders as code block`() {
        val md = "```kotlin\nval x = 1\n```"
        val blocks = EnhancedMarkdownParser.parse(md)
        assertTrue(blocks.single() is EnhancedBlock.CodeBlock)
    }

    @Test
    fun `unclosed math block stays math while streaming`() {
        val md = "The formula:\n$$\nE = mc^2"
        val blocks = EnhancedMarkdownParser.parse(md, isStreaming = true)
        assertTrue(blocks.last() is EnhancedBlock.MathBlock)
    }

    @Test
    fun `partial bold renders as plain text and does not crash`() {
        val blocks = EnhancedMarkdownParser.parse("This is **bold and unfini", isStreaming = true)
        assertTrue(blocks.single() is EnhancedBlock.Paragraph)
    }

    @Test
    fun `lone table row without separator must not deadlock the parser`() {
        // Regression: "| A | B |" (no separator row) used to be excluded from
        // the paragraph catch-all without consuming the line → infinite loop.
        val blocks = EnhancedMarkdownParser.parse("| A | B |", isStreaming = true)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is EnhancedBlock.Paragraph)
        assertEquals("| A | B |", (blocks[0] as EnhancedBlock.Paragraph).text)
    }

    @Test
    fun `lone hash line must not deadlock the parser`() {
        val blocks = EnhancedMarkdownParser.parse("###", isStreaming = true)
        assertTrue(blocks.single() is EnhancedBlock.Paragraph)
    }

    @Test
    fun `lone bullet dash must not deadlock the parser`() {
        val blocks = EnhancedMarkdownParser.parse("- ", isStreaming = true)
        assertTrue(blocks.single() is EnhancedBlock.Paragraph)
    }

    @Test
    fun `lone numbered marker must not deadlock the parser`() {
        val blocks = EnhancedMarkdownParser.parse("1. ", isStreaming = true)
        assertTrue(blocks.single() is EnhancedBlock.Paragraph)
    }

    @Test
    fun `complete table still renders`() {
        val md = "| A | B |\n|---|---|\n| 1 | 2 |"
        val blocks = EnhancedMarkdownParser.parse(md)
        assertTrue(blocks.single() is EnhancedBlock.Table)
    }

    @Test
    fun `partial heading renders as heading while streaming`() {
        val blocks = EnhancedMarkdownParser.parse("### My heading", isStreaming = true)
        assertTrue(blocks.single() is EnhancedBlock.Heading)
    }

    @Test
    fun `mixed streaming fragment parses without hanging`() {
        val md = "Answer:\n\n- first item\n- second\n\n```bash\nls -la\ncat foo"
        val blocks = EnhancedMarkdownParser.parse(md, isStreaming = true)
        assertTrue(blocks.any { it is EnhancedBlock.BulletList })
        assertTrue(blocks.any { it is EnhancedBlock.CodeBlock })
    }
}
