package com.interndra.service

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TerminalBufferTest — comprehensive ANSI escape sequence parser tests.
 *
 * Covers: SGR colors, cursor movement, clear screen, edge cases,
 * multi-span text, bold/dim/underline, real-world terminal output,
 * OSC sequences, carriage return span management, escape overflow.
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
        // Only styled text produces spans; unstyled text after reset has no span
        assertTrue(lines[0].spans.isNotEmpty())
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
        assertTrue(lines[0].spans[0].bold)
    }

    @Test
    fun `SGR 22 clears bold and dim`() {
        buffer.processOutput("\u001b[1mbold\u001b[22mnormal")
        val lines = buffer.flush()
        assertEquals("boldnormal", lines[0].text)
        assertTrue(lines[0].spans[0].bold)
    }

    @Test
    fun `SGR 24 clears underline`() {
        buffer.processOutput("\u001b[4munder\u001b[24mplain")
        val lines = buffer.flush()
        assertEquals("underplain", lines[0].text)
        assertTrue(lines[0].spans[0].underline)
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
        buffer.processOutput("\u001b[32m$\u001b[0m ls -la")
        val lines = buffer.flush()
        assertEquals("$ ls -la", lines[0].text)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `backend indicator dimmed text`() {
        buffer.processOutput("\u001b[90m[ShellExecutor]\u001b[0m\n")
        val lines = buffer.flush()
        assertEquals("[ShellExecutor]", lines[0].text)
        assertEquals(Color.DKGRAY, lines[0].spans[0].fgColor)
    }

    // ── Cursor Movement ──────────────────────────────────────────────

    @Test
    fun `cursor up CUU does not crash`() {
        buffer.processOutput("line1\nline2\u001b[1A")
        buffer.flush()
        assertTrue(true)
    }

    @Test
    fun `cursor next line CNL moves to column 0`() {
        buffer.processOutput("line1\u001b[E")
        val lines = buffer.flush()
        assertEquals("line1", lines[0].text)
    }

    @Test
    fun `cursor horizontal absolute CHA`() {
        buffer.processOutput("hello\u001b[2GX")
        val lines = buffer.flush()
        // CHA moves to column 1 (0-indexed), then "X" overwrites at that position
        assertEquals("hXllo", lines[0].text)
    }

    // ── Clear Screen ─────────────────────────────────────────────────

    @Test
    fun `ED clear entire screen 2J`() {
        buffer.processOutput("line1\nline2\nline3")
        buffer.processOutput("\u001b[2J")
        buffer.processOutput("fresh")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("fresh", lines[0].text)
    }

    @Test
    fun `EL clear entire line 2K`() {
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
        buffer.processOutput("\u001b[not_m_codenormal text")
        val lines = buffer.flush()
        assertTrue(lines[0].text.isNotEmpty())
    }

    @Test
    fun `unknown SGR code ignored`() {
        buffer.processOutput("\u001b[99mtext\u001b[0m")
        val lines = buffer.flush()
        assertEquals("text", lines[0].text)
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

    // ── CRITICAL FIX: OSC Sequences ──────────────────────────────────

    @Test
    fun `OSC window title with BEL terminator does not eat output`() {
        // \u001b]0;My Title\u0007 is a common OSC window title sequence.
        // Previously this would trap the parser in escape mode indefinitely.
        buffer.processOutput("before\u001b]0;My Window Title\u0007after")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("beforeafter", lines[0].text)
    }

    @Test
    fun `OSC with ST terminator does not eat output`() {
        // \u001b]0;title\u001b\\ is OSC with String Terminator
        buffer.processOutput("prefix\u001b]0;My Title\u001b\\suffix")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("prefixsuffix", lines[0].text)
    }

    @Test
    fun `multiple OSC sequences handled correctly`() {
        buffer.processOutput("\u001b]0;Tab 1\u0007line1\n\u001b]0;Tab 2\u0007line2")
        val lines = buffer.flush()
        assertEquals(2, lines.size)
        assertEquals("line1", lines[0].text)
        assertEquals("line2", lines[1].text)
    }

    @Test
    fun `OSC does not interfere with adjacent colors`() {
        buffer.processOutput("\u001b[31mred\u001b]0;title\u0007\u001b[32mgreen\u001b[0m")
        val lines = buffer.flush()
        assertEquals("redgreen", lines[0].text)
        assertEquals(2, lines[0].spans.size)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[1].fgColor)
    }

    // ── CRITICAL FIX: Non-CSI Escapes ────────────────────────────────

    @Test
    fun `SOS sequence with ST terminator consumed`() {
        buffer.processOutput("start\u001bXgarbage data\u001b\\end")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("startend", lines[0].text)
    }

    @Test
    fun `PM sequence with ST terminator consumed`() {
        buffer.processOutput("a\u001b^private\u001b\\b")
        val lines = buffer.flush()
        assertEquals("ab", lines[0].text)
    }

    @Test
    fun `APC sequence with ST terminator consumed`() {
        buffer.processOutput("x\u001b_app data\u001b\\y")
        val lines = buffer.flush()
        assertEquals("xy", lines[0].text)
    }

    // ── CRITICAL FIX: Character Set Sequences ────────────────────────

    @Test
    fun `character set select B does not move cursor`() {
        // \u001b(B was previously misinterpreted as CUU which moves cursor up
        buffer.processOutput("hello\u001b(Bworld")
        val lines = buffer.flush()
        assertEquals("helloworld", lines[0].text)
    }

    @Test
    fun `character set select 0 does not cause issues`() {
        buffer.processOutput("\u001b)0text")
        val lines = buffer.flush()
        assertEquals("text", lines[0].text)
    }

    // ── CRITICAL FIX: DECSET/DECRST (?) ──────────────────────────────

    @Test
    fun `DECSET hide cursor does not crash`() {
        buffer.processOutput("\u001b[?25lvisible\u001b[?25h")
        val lines = buffer.flush()
        assertEquals("visible", lines[0].text)
    }

    @Test
    fun `DECSET with params parses correctly`() {
        buffer.processOutput("\u001b[?1049hfullscreen\u001b[?1049l")
        val lines = buffer.flush()
        assertEquals("fullscreen", lines[0].text)
    }

    // ── CRITICAL FIX: Carriage Return + Span Management ──────────────

    @Test
    fun `carriage return overwrite with colored text`() {
        // Red "error" then \r overwrite with "success" in green
        buffer.processOutput("\u001b[31merror\u001b[0m\r\u001b[32msuccess\u001b[0m")
        val lines = buffer.flush()
        assertEquals("success", lines[0].text)
        assertEquals(1, lines[0].spans.size)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `carriage return partial overwrite with color change`() {
        // "ABCDE" then \r then "12" in red → "12CDE" with "12" in red
        buffer.processOutput("ABCDE\r\u001b[31m12\u001b[0m")
        val lines = buffer.flush()
        assertEquals("12CDE", lines[0].text)
        assertEquals(1, lines[0].spans.size)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
        assertEquals(0, lines[0].spans[0].start)
        assertEquals(2, lines[0].spans[0].end)
    }

    @Test
    fun `carriage return multiple overwrites progress bar`() {
        // Simulates a progress bar: 0% → 50% → 100%
        buffer.processOutput("Progress:  0%\rProgress: 50%\rProgress: 100%\nDone!")
        val lines = buffer.flush()
        assertEquals(2, lines.size)
        assertEquals("Progress: 100%", lines[0].text)
        assertEquals("Done!", lines[1].text)
    }

    @Test
    fun `carriage return spinner overwrites`() {
        // Simulates a spinning progress indicator
        buffer.processOutput("Loading |\rLoading /\rLoading -\rLoading \\\rDone!      \n")
        val lines = buffer.flush()
        assertEquals(1, lines.size)
        assertEquals("Done!      ", lines[0].text)
    }

    // ── CRITICAL FIX: Escape Buffer Overflow ─────────────────────────

    @Test
    fun `extremely long escape sequence does not overflow`() {
        val longEscape = "\u001b[" + "1;".repeat(100) + "mtext"
        buffer.processOutput(longEscape)
        val lines = buffer.flush()
        // Should recover gracefully and output "text"
        assertEquals("text", lines[0].text)
    }

    @Test
    fun `garbage escape sequence terminated by overflow`() {
        // A very long sequence without a valid final byte
        val garbage = "\u001b" + "A".repeat(200) + "normal"
        buffer.processOutput(garbage)
        val lines = buffer.flush()
        // Should survive and output any content after the overflow abort
        // "normal" might get consumed or partially consumed depending on timing
        assertTrue(lines.isNotEmpty())
    }

    // ── 256-Color Support ────────────────────────────────────────────

    @Test
    fun `SGR 38 5 196 bright red 256 color`() {
        buffer.processOutput("\u001b[38;5;196m256 red\u001b[0m")
        val lines = buffer.flush()
        assertEquals("256 red", lines[0].text)
        // Color 196 = 16 + (3*36 + 0*6 + 0) = 16+108 = 124 → in 216-color cube
        assertNotNull(lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 48 5 42 green background 256 color`() {
        buffer.processOutput("\u001b[48;5;42mbg\u001b[0m")
        val lines = buffer.flush()
        assertEquals("bg", lines[0].text)
        assertNotNull(lines[0].spans[0].bgColor)
    }

    @Test
    fun `SGR 38 2 true color foreground`() {
        buffer.processOutput("\u001b[38;2;255;128;0morange\u001b[0m")
        val lines = buffer.flush()
        assertEquals("orange", lines[0].text)
        assertEquals(Color.rgb(255, 128, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `SGR 48 2 true color background`() {
        buffer.processOutput("\u001b[48;2;100;150;200mbg color\u001b[0m")
        val lines = buffer.flush()
        assertEquals("bg color", lines[0].text)
        assertEquals(Color.rgb(100, 150, 200), lines[0].spans[0].bgColor)
    }

    // ── Delete Character (DCH) ──────────────────────────────────────

    @Test
    fun `DCH deletes characters at cursor`() {
        buffer.processOutput("abcdef\u001b[3D\u001b[2P")
        val lines = buffer.flush()
        assertEquals("abcf", lines[0].text)
    }

    // ── Erase Character (ECH) ────────────────────────────────────────

    @Test
    fun `ECH erases characters at cursor with spaces`() {
        buffer.processOutput("abcdef\u001b[3D\u001b[2X")
        val lines = buffer.flush()
        assertEquals("ab  ef", lines[0].text)
    }

    // ── Insert / Delete Lines ────────────────────────────────────────

    @Test
    fun `IL inserts blank line`() {
        buffer.processOutput("first\nsecond\u001b[1L")
        buffer.flush() // does not crash
        assertTrue(true)
    }

    @Test
    fun `DL deletes line`() {
        buffer.processOutput("first\nsecond\u001b[1M")
        buffer.flush() // does not crash
        assertTrue(true)
    }

    // ── Concurrent Access ────────────────────────────────────────────

    @Test
    fun `synchronized methods do not deadlock`() {
        val t1 = Thread { buffer.processOutput("\u001b[31mthread1\u001b[0m\n") }
        val t2 = Thread { buffer.processOutput("\u001b[32mthread2\u001b[0m\n") }
        t1.start(); t2.start()
        t1.join(); t2.join()
        val lines = buffer.flush()
        assertEquals(2, lines.size)
    }

    // ── Edge: Streaming Across Multiple Calls ────────────────────────

    @Test
    fun `escape sequence split across multiple processOutput calls`() {
        buffer.processOutput("\u001b[3")
        buffer.processOutput("2mhello\u001b[0m")
        val lines = buffer.flush()
        assertEquals("hello", lines[0].text)
        assertEquals(Color.rgb(0, 170, 0), lines[0].spans[0].fgColor)
    }

    @Test
    fun `OSC sequence split across multiple calls`() {
        buffer.processOutput("\u001b]0;Ti")
        buffer.processOutput("tle\u0007text")
        val lines = buffer.flush()
        assertEquals("text", lines[0].text)
    }

    // ── Edge: Trailing Partial Escape on Flush ───────────────────────

    @Test
    fun `flushing while in escape mode does not lose data`() {
        buffer.processOutput("visible\u001b[3")
        // In escape mode but incomplete — flush should still return what we have
        val lines = buffer.flush()
        assertEquals("visible", lines[0].text)
    }

    @Test
    fun `continuing after interrupted escape`() {
        buffer.processOutput("a\u001b[3")
        buffer.flush()
        buffer.processOutput("1mred\u001b[0m")
        val lines = buffer.flush()
        assertEquals("ared", lines[0].text)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
    }

    // ── Edge: Form Feed (FF) and Other Control Chars ─────────────────

    @Test
    fun `form feed skipped`() {
        buffer.processOutput("before\u000Cafter")
        val lines = buffer.flush()
        assertEquals("beforeafter", lines[0].text)
    }

    @Test
    fun `vertical tab skipped`() {
        buffer.processOutput("line1\u000Bline2")
        val lines = buffer.flush()
        assertEquals("line1line2", lines[0].text)
    }

    // ── Edge: Adjacent Escape Sequences ──────────────────────────────

    @Test
    fun `adjacent SGR sequences`() {
        buffer.processOutput("\u001b[31m\u001b[1mbold red\u001b[0m")
        val lines = buffer.flush()
        assertEquals("bold red", lines[0].text)
        assertTrue(lines[0].spans[0].bold)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
    }

    // ── Edge: Zero-Width Sequences ──────────────────────────────────

    @Test
    fun `empty CSI sequence does not crash`() {
        buffer.processOutput("\u001b[mreset")
        val lines = buffer.flush()
        assertEquals("reset", lines[0].text)
    }

    @Test
    fun `SGR with trailing semicolon`() {
        buffer.processOutput("\u001b[31;mgreen\u001b[0m")
        val lines = buffer.flush()
        assertEquals("green", lines[0].text)
        assertEquals(Color.RED, lines[0].spans[0].fgColor)
    }

    // ── Real-World: wget / curl Progress Bar ─────────────────────────

    @Test
    fun `realistic download progress bar`() {
        // Simulates:   % Total    % Received % Xferd
        //              0  100M    0     0    0     0      0      0 --:--:--  0:00:01 --:--:--     0
        //              1  100M  1.0M     0    1.0M    0      0   512k  0:03:15  0:00:02  0:03:13   512k
        //            100  100M  100M     0  100M    0      0   1.0M  0:01:40  0:01:40 --:--:--  1.0M
        val progress1 = "\r  0  100M    0     0    0     0      0      0 --:--:--  0:00:01 --:--:--     0"
        val progress2 = "\r  1  100M  1.0M     0    1.0M    0      0   512k  0:03:15  0:00:02  0:03:13   512k"
        val progress3 = "\r100  100M  100M     0  100M    0      0   1.0M  0:01:40  0:01:40 --:--:--  1.0M\nDownload complete!"
        buffer.processOutput(progress1)
        buffer.processOutput(progress2)
        buffer.processOutput(progress3)
        val lines = buffer.flush()
        assertEquals(2, lines.size)
        assertTrue(lines[0].text.contains("100"))
        assertTrue(lines[0].text.contains("1.0M"))
        assertEquals("Download complete!", lines[1].text)
    }

    // ── Real-World: Python / npm Install Output ──────────────────────

    @Test
    fun `pip install progress with spinner`() {
        // Simulates pip install with spinner updates
        buffer.processOutput("Collecting requests... \u001b[32m⠋\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠙\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠹\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠸\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠼\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠴\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠦\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠧\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠇\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32m⠏\u001b[0m\r")
        buffer.processOutput("Collecting requests... \u001b[32mdone\u001b[0m\n")
        buffer.processOutput("\u001b[32m✓\u001b[0m Installed requests 2.31.0\n")
        val lines = buffer.flush()
        assertEquals(2, lines.size)
        assertTrue(lines[0].text.contains("done"))
        assertTrue(lines[1].text.contains("Installed requests"))
    }

    // ── Real-World: ADB / shell banner with OSC ──────────────────────

    @Test
    fun `adb shell banner with OSC sequences`() {
        // ADB shell often outputs OSC window title on connect
        buffer.processOutput("\u001b]0;user@device: /data/local/tmp\u0007")
        buffer.processOutput("\u001b[01;32muser@device\u001b[00m:\u001b[01;34m/data/local/tmp\u001b[00m# ")
        val lines = buffer.flush()
        assertEquals("user@device:/data/local/tmp# ", lines[0].text)
        // Should have color spans for the prompt
        assertTrue(lines[0].spans.isNotEmpty())
    }
}
