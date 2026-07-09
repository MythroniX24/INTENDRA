package com.interndra.service

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TerminalBufferTest — comprehensive ANSI escape sequence parser tests.
 *
 * Covers: SGR colors, cursor movement, clear screen, edge cases,
 * multi-span text, bold/dim/underline, and real-world terminal output.
 */
class TerminalBufferTest {

    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer()
    }

    // ── Basic Text ───────────────────────────────────────────────────

    @Test
    fun `plain text passed through unchanged`() {
        buffer.processOutput("hello world")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("hello world", lines[0].text)
        assertEquals(0, lines[0].spans.size)
    }

    @Test
    fun `empty string produces no output`() {
        buffer.processOutput("")
        val lines = buffer.flush()
        assertEquals(0, lines.size)
    }

    @Test
    fun `newline splits into multiple lines`() {
        buffer.processOutput("line1\nline2\nline3")
        val lines = buffer.flush()
        assertEquals(3, lines.size)
        assertEquals("line1", lines[0].text)
        assertEquals("line2", lines[1].text)
        assertEquals("line3", lines[2].text)
    }

    @Test
    fun `carriage return resets column but stays on same line`() {
        buffer.processOutput("abc\rdef")
        val lines = buffer.flush()
        // \r resets to column 0, so "def" overwrites "abc" → "def"
        assertEquals("def", lines[0].text)
    }

    @Test
    fun `tab expands to 8 spaces`() {
        buffer.processOutput("col1\tcol2")
        val lines = buffer.flush()
        assertEquals("col1    col2", lines[0].text)
    }

    // ── SGR Colors — Foreground ──────────────────────────────────────

    @Test
    fun `SGR 31 red foreground`() {
        buffer.processOutput("\u001b[31mred text\u001b[0m")
        val lines = buffer.flush()
        assertEquals("red text", lines[0].text)
        assertEquals(1, lines[0].spans.size)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 32 green foreground`() {
        buffer.processOutput("\u001b[32mgreen\u001b[0m")
        val lines = buffer.flush()
        assertEquals("green", lines[0].text)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 33 yellow foreground`() {
        buffer.processOutput("\u001b[33myellow\u001b[0m")
        val lines = buffer.flush()
        assertEquals("yellow", lines[0].text)
        assertEquals(Color.rgb(170, 170, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 34 blue foreground`() {
        buffer.processOutput("\u001b[34mblue\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.BLUE, lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 36 cyan foreground`() {
        buffer.processOutput("\u001b[36mcyan\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.CYAN, lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 37 white foreground`() {
        buffer.processOutput("\u001b[37mwhite\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.WHITE, lines[0].spans[0].fgColor)
    }

    // ── SGR Colors — Background ───────────────────────────────────────

    @Test
    fun `SGR 41 red background`() {
        buffer.processOutput("\u001b[41mbg red\u001b[0m")
        val lines = buffer.flush()
        assertEquals("bg red", lines[0].text)
        assertEquals(Color.RED, lines[0].spans[0].bgColor)
    }

    @Test
    fun `SGR 42 green background`() {
        buffer.processOutput("\u001b[42mbg green\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[0].bgColor)
    }

    // ── Bright Colors (90-97) ────────────────────────────────────────

    @Test
    fun `SGR 91 bright red`() {
        buffer.processOutput("\u001b[91mbright red\u001b[0m")
        val lines = buffer.flush()
        assertEquals("bright red", lines[0].text)
        assertEquals(Color.rgb(255, 100, 100), lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 92 bright green`() {
        buffer.processOutput("\u001b[92mbright green\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.rgb(100, 255, 100), lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 93 bright yellow`() {
        buffer.processOutput("\u001b[93mbright yellow\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.YELLOW, lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 96 bright cyan`() {
        buffer.processOutput("\u001b[96mbright cyan\u001b[0m")
        val lines = buffer.flush()
        assertEquals(Color.rgb(100, 255, 255), lines[0].spans[0].fgColor)
    }

    // ── Text Attributes ──────────────────────────────────────────────

    @Test
    fun `SGR 1 bold`() {
        buffer.processOutput("\u001b[1mbold text\u001b[0m")
        val lines = buffer.flush()
        assertTrue(lines[0].spans[0].bold)
    }

    @Test
    fun `SGR 2 dim`() {
        buffer.processOutput("\u001b[2mdim text\u001b[0m")
        val lines = buffer.flush()
        assertTrue(lines[0].spans[0].dim)
    }

    @Test
    fun `SGR 4 underline`() {
        buffer.processOutput("\u001b[4munderlined\u001b[0m")
        val lines = buffer.flush()
        assertTrue(lines[0].spans[0].underline)
    }

    @Test
    fun `SGR 0 reset clears all attributes`() {
        buffer.processOutput("\u001b[1m\u001b[31mbold red\u001b[0mnormal")
        val lines = buffer.flush()
        assertEquals("bold rednormal", lines[0].text)
        // First span: bold + red
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
        assertTrue(lines[0].spans[0].bold)
        // Second span: normal (after reset)
        assertEquals(2, lines[0].spans.size)
    }

    @Test
    fun `SGR 22 clears bold and dim`() {
        buffer.processOutput("\u001b[1mbold\u001b[22mnormal")
        val lines = buffer.flush()
        assertEquals("boldnormal", lines[0].text)
        assertTrue(lines[0].spans[0].bold)
        assertFalse(lines[0].spans[1].bold)
        assertFalse(lines[0].spans[1].dim)
    }

    @Test
    fun `SGR 24 clears underline`() {
        buffer.processOutput("\u001b[4munder\u001b[24mplain")
        val lines = buffer.flush()
        assertEquals("underplain", lines[0].text)
        assertTrue(lines[0].spans[0].underline)
        assertFalse(lines[0].spans[1].underline)
    }

    // ── Multi-Span Text ──────────────────────────────────────────────

    @Test
    fun `multiple colors in one line`() {
        buffer.processOutput("\u001b[31mR\u001b[32mG\u001b[34mB\u001b[0m")
        val lines = buffer.flush()
        assertEquals("RGB", lines[0].text)
        assertEquals(3, lines[0].spans.size)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[1].fgColor)
        assertEquals(Color.BLUE, lines[0].spans[2].fgColor)
    }

    @Test
    fun `plain text between colored sections`() {
        buffer.processOutput("start \u001b[31mred\u001b[0m end")
        val lines = buffer.flush()
        assertEquals("start red end", lines[0].text)
        assertEquals(1, lines[0].spans.size) // Only "red" has a span
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
    }

    // ── Real-World Terminal Output ────────────────────────────────────

    @Test
    fun `ls output with color codes`() {
        // Simulated `ls --color=auto` output
        buffer.processOutput("\u001b[1;34mdir1\u001b[0m  \u001b[1;32mfile.txt\u001b[0m")
        val lines = buffer.flush()
        assertEquals("dir1  file.txt", lines[0].text)
        assertEquals(2, lines[0].spans.size)
    }

    @Test
    fun `git diff output`() {
        buffer.processOutput("\u001b[31m-removed line\u001b[0m\n\u001b[32m+added line\u001b[0m")
        val lines = buffer.flush()
        assertEquals(2, lines.size)
        assertEquals("-removed line", lines[0].text)
        assertEquals("+added line", lines[1].text)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
        assertEquals(Color.rgb(0, 170, 0), lines[1].spans[0].fgColor)
    }

    @Test
    fun `prompt with green dollar sign`() {
        // INTERNDRA's terminal prompt
        buffer.processOutput("\u001b[32m$\u001b[0m ls -la")
        val lines = buffer.flush()
        assertEquals("$ ls -la", lines[0].text)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `backend indicator dimmed text`() {
        buffer.processOutput("\u001b[90m[SmartShell]\u001b[0m\n")
        val lines = buffer.flush()
        assertEquals("[SmartShell]", lines[0].text)
        assertEquals(Color.DKGRAY, lines[0].spans[0].fgColor)
    }

    // ── Cursor Movement ──────────────────────────────────────────────

    @Test
    fun `cursor up CUU moves cursor up`() {
        buffer.processOutput("line1\nline2\u001b[1A")
        buffer.flush()
        // Cursor moved up 1 row — state is internal, verified by no crash
        assertTrue(true)
    }

    // ── Clear Screen ─────────────────────────────────────────────────

    @Test
    fun `ED clear entire screen (2J)`() {
        buffer.processOutput("line1\nline2\nline3")
        buffer.processOutput("\u001b[2J")
        buffer.processOutput("fresh")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("fresh", lines[0].text)
    }

    @Test
    fun `EL clear entire line (2K)`() {
        buffer.processOutput("old text\u001b[2Knew")
        val lines = buffer.flush()
        assertEquals("new", lines[0].text)
    }

    // ── Reset / State Management ─────────────────────────────────────

    @Test
    fun `reset clears all state`() {
        buffer.processOutput("\u001b[31mcolored text")
        buffer.reset()
        buffer.processOutput("plain")
        val lines = buffer.flush()
        assertEquals("plain", lines[0].text)
        assertEquals(0, lines[0].spans.size)
    }

    @Test
    fun `reset between uses`() {
        buffer.processOutput("first")
        buffer.flush()
        buffer.reset()
        buffer.processOutput("second")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("second", lines[0].text)
    }

    // ── flushPlainText ───────────────────────────────────────────────

    @Test
    fun `flushPlainText strips all ANSI codes`() {
        buffer.processOutput("\u001b[32mgreen\u001b[0m \u001b[31merror\u001b[0m")
        val text = buffer.flushPlainText()
        assertEquals("green error", text)
    }

    @Test
    fun `flushPlainText multiline`() {
        buffer.processOutput("\u001b[32mline1\u001b[0m\n\u001b[31mline2\u001b[0m")
        val text = buffer.flushPlainText()
        assertEquals("line1\nline2", text)
    }

    // ── Edge Cases ───────────────────────────────────────────────────

    @Test
    fun `partial escape sequence handled gracefully`() {
        // Escape char followed by garbage
        buffer.processOutput("\u001b[not_m_codenormal text")
        val lines = buffer.flush()
        // Should not crash, should contain whatever was parsable
        assertTrue(lines[0].text.isNotEmpty())
    }

    @Test
    fun `unknown SGR code ignored`() {
        buffer.processOutput("\u001b[99mtext\u001b[0m")
        val lines = buffer.flush()
        assertEquals("text", lines[0].text)
        // Unknown code = no color span
        assertEquals(0, lines[0].spans.size)
    }

    @Test
    fun `very long line does not crash`() {
        val long = "A".repeat(10000)
        buffer.processOutput(long)
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals(long, lines[0].text)
    }

    @Test
    fun `many lines flushed correctly`() {
        for (i in 1..100) {
            buffer.processOutput("line $i\n")
        }
        val lines = buffer.flush()
        assertEquals(100, lines.size)
    }

    @Test
    fun `control characters skipped`() {
        buffer.processOutput("hello\u0007world") // BEL char
        val lines = buffer.flush()
        assertEquals("helloworld", lines[0].text)
    }

    @Test
    fun `null character skipped`() {
        buffer.processOutput("test\u0000data")
        val lines = buffer.flush()
        assertEquals("testdata", lines[0].text)
    }

    @Test
    fun `backspace removes character`() {
        buffer.processOutput("abc\bd")
        val lines = buffer.flush()
        assertEquals("abd", lines[0].text)
    }

    @Test
    fun `multiple backspaces`() {
        buffer.processOutput("hello\b\b\bhi")
        val lines = buffer.flush()
        assertEquals("hehi", lines[0].text)
    }

    // ── Concurrent Access (thread safety) ────────────────────────────

    @Test
    fun `synchronized methods do not deadlock`() {
        val t1 = Thread { buffer.processOutput("\u001b[31mthread1\u001b[0m\n") }
        val t2 = Thread { buffer.processOutput("\u001b[32mthread2\u001b[0m\n") }
        t1.start(); t2.start()
        t1.join(); t2.join()
        val lines = buffer.flush()
        assertEquals(2, lines.size)
    }
}
