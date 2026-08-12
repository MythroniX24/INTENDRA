package com.interndra.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 performance & robustness guard for [EnhancedMarkdownParser].
 *
 * These are regression tests for the exact scale the app promises to handle
 * smoothly: 10k+ character responses, 1000-line code blocks, 200-row tables
 * and repeated streaming reparses. Time budgets are deliberately generous so
 * slow CI machines don't flake — the real assertion is that the parser
 * completes (no deadlock) and stays structurally correct.
 */
class MarkdownPerformanceTest {

    // ── 10k+ char mixed response ────────────────────────────────────────

    @Test
    fun `10k char mixed response parses into many blocks quickly`() {
        val sb = StringBuilder()
        repeat(30) { h ->
            sb.append("# Heading $h\n\n")
            sb.append("Paragraph number $h with **bold** and `code` and a [link](https://example.com).\n\n")
            repeat(5) { i ->
                sb.append("- bullet item $i with some longer text to make it realistic\n")
            }
            sb.append("\n")
            repeat(4) { i ->
                sb.append("$i. numbered step $i\n")
            }
            sb.append("\n| Col A | Col B |\n|---|---|\n")
            repeat(3) { r ->
                sb.append("| v$r-a | v$r-b |\n")
            }
            sb.append("\n```kotlin\nfun step$h() {\n    val x = $h\n    println(\"done\")\n}\n```\n\n")
        }
        val md = sb.toString()
        assertTrue("fixture should be a 10k+ char response", md.length > 10_000)

        val start = System.nanoTime()
        val blocks = EnhancedMarkdownParser.parse(md)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("expected 30+ blocks for 30 sections, got ${blocks.size}", blocks.size >= 30)
        assertEquals(30, blocks.count { it is EnhancedBlock.CodeBlock })
        assertEquals(30, blocks.count { it is EnhancedBlock.Heading })
        assertEquals(30, blocks.count { it is EnhancedBlock.BulletList })
        assertEquals(30, blocks.count { it is EnhancedBlock.Table })
        assertTrue("parse of 10k+ chars took ${elapsedMs}ms (budget 3000ms)", elapsedMs < 3000)
    }

    // ── 1000-line code block ─────────────────────────────────────────────

    @Test
    fun `1000 line code block parses into a single code block with exact content`() {
        val codeLines = (1..1000).map { "val line$it = $it // some content" }
        val md = "```kotlin\n${codeLines.joinToString("\n")}\n```"

        val start = System.nanoTime()
        val blocks = EnhancedMarkdownParser.parse(md)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(1, blocks.size)
        val code = blocks[0] as EnhancedBlock.CodeBlock
        assertEquals("kotlin", code.language)
        // Content must survive intact — nothing truncated at parse time.
        assertEquals(codeLines.size, code.code.lines().size)
        assertTrue(code.code.contains("val line1000 = 1000"))
        assertTrue("1000-line code block parse took ${elapsedMs}ms (budget 3000ms)", elapsedMs < 3000)
    }

    // ── 200-row table ────────────────────────────────────────────────────

    @Test
    fun `200 row table parses with all rows intact`() {
        val sb = StringBuilder("| ID | Name | Score |\n|---|---|---|\n")
        repeat(200) { r -> sb.append("| $r | name$r | ${r * 3} |\n") }

        val blocks = EnhancedMarkdownParser.parse(sb.toString())
        assertEquals(1, blocks.size)
        val table = blocks[0] as EnhancedBlock.Table
        assertEquals(3, table.headers.size)
        assertEquals(200, table.rows.size)
        assertEquals(listOf("199", "name199", "597"), table.rows.last())
    }

    // ── Huge single paragraph (no newlines) ──────────────────────────────

    @Test
    fun `10k char paragraph without newlines does not deadlock`() {
        val text = "word ".repeat(2000) // 10k chars, single logical paragraph
        val blocks = EnhancedMarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is EnhancedBlock.Paragraph)
    }

    // ── Streaming: repeated reparses of a growing buffer ─────────────────

    @Test
    fun `growing stream reparse stays within budget`() {
        // Simulates ~50 streaming commits while the AI types a long answer
        // (code block + prose). Every commit re-parses the whole buffer.
        val sb = StringBuilder()
        sb.append("Here is the full answer:\n\n")
        var step = 0
        val start = System.nanoTime()
        // Small per-commit chunks (like real streaming tokens) so we get well
        // over 50 simulated commits inside the 8k-char buffer.
        while (sb.length < 8_000) {
            sb.append("```kotlin\n")
            repeat(4) { i -> sb.append("fun f$step$i() = ${step * i}\n") }
            sb.append("```\n\n")
            sb.append("And some **prose** with $step more context to keep growing.\n\n")
            step++
            // Re-parse the growing buffer on every commit — just like the
            // streaming renderer does.
            val blocks = EnhancedMarkdownParser.parse(sb.toString(), isStreaming = true)
            assertTrue(blocks.isNotEmpty())
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("streaming reparse took ${elapsedMs}ms (budget 5000ms)", elapsedMs < 5000)
        assertTrue("expected 50+ simulated commits, got $step", step >= 50)
    }

    // ── 100+ messages worth of content ───────────────────────────────────

    @Test
    fun `100 message conversation parses without issues`() {
        val sb = StringBuilder()
        repeat(100) { m ->
            sb.append(if (m % 2 == 0) "User question number $m about **things**?\n\n" else {
                "```kotlin\nval answer$m = \"response with code\"\n```\n\n"
            })
        }
        val blocks = EnhancedMarkdownParser.parse(sb.toString())
        assertEquals(50, blocks.count { it is EnhancedBlock.CodeBlock })
        assertEquals(50, blocks.count { it is EnhancedBlock.Paragraph })
    }
}
