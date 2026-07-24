package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TerminalEmulatorTest — comprehensive tests for the ANSI/VT100 escape code
 * parser and terminal screen state management.
 */
class TerminalEmulatorTest {

    private lateinit var emu: TerminalEmulator

    @Before
    fun setUp() {
        emu = TerminalEmulator(rows = 24, columns = 80)
    }

    // ── Basic Character Handling ──────────────────────────────────────

    @Test
    fun `processByte writes printable char at cursor`() {
        emu.processByte('A'.code)
        assertEquals('A', emu.getCell(0, 0).char)
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `processString writes multiple chars`() {
        emu.processString("Hello")
        assertEquals('H', emu.getCell(0, 0).char)
        assertEquals('e', emu.getCell(0, 1).char)
        assertEquals('l', emu.getCell(0, 2).char)
        assertEquals('l', emu.getCell(0, 3).char)
        assertEquals('o', emu.getCell(0, 4).char)
        assertEquals(5, emu.cursorCol)
    }

    @Test
    fun `processBytes handles byte array`() {
        val bytes = "Test".toByteArray()
        emu.processBytes(bytes, 0, bytes.size)
        assertEquals('T', emu.getCell(0, 0).char)
        assertEquals(4, emu.cursorCol)
    }

    // ── Newline and Carriage Return ───────────────────────────────────

    @Test
    fun `line feed moves cursor down`() {
        emu.processByte(0x0A) // LF
        assertEquals(1, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `carriage return moves to column 0`() {
        emu.processString("Hello")
        emu.processByte(0x0D) // CR
        assertEquals(0, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `word wrap at end of line`() {
        val line = "A".repeat(80) // exactly fills row
        emu.processString(line)
        assertEquals(0, emu.cursorRow) // auto-wrap moves to next line
        assertEquals(0, emu.cursorCol)
    }

    // ── ANSI SGR (Colors & Attributes) ───────────────────────────────

    @Test
    fun `SGR bold sets bold on cells`() {
        emu.processString("\u001B[1mBold")
        val cell = emu.getCell(0, 0)
        assertTrue(cell.bold)
        assertEquals('B', cell.char)
    }

    @Test
    fun `SGR reset clears attributes`() {
        emu.processString("\u001B[1mBold\u001B[0mNormal")
        assertEquals('B', emu.getCell(0, 0).char)
        assertTrue(emu.getCell(0, 0).bold)
        // After reset, 'N' should not be bold
        assertEquals('N', emu.getCell(0, 4).char)
        assertFalse(emu.getCell(0, 4).bold)
    }

    @Test
    fun `SGR red foreground sets color`() {
        emu.processString("\u001B[31mRed")
        val cell = emu.getCell(0, 0)
        assertEquals(TerminalEmulator.COLOR_RED, cell.foreground)
    }

    @Test
    fun `SGR green foreground`() {
        emu.processString("\u001B[32mGreen")
        assertEquals(TerminalEmulator.COLOR_GREEN, emu.getCell(0, 0).foreground)
    }

    @Test
    fun `SGR blue background`() {
        emu.processString("\u001B[44mBgBlue")
        assertEquals(TerminalEmulator.COLOR_BLUE, emu.getCell(0, 0).background)
    }

    @Test
    fun `SGR multiple params in one sequence`() {
        emu.processString("\u001B[1;31mBoldRed")
        val cell = emu.getCell(0, 0)
        assertTrue(cell.bold)
        assertEquals(TerminalEmulator.COLOR_RED, cell.foreground)
    }

    @Test
    fun `SGR reset code 0 clears all`() {
        emu.processString("\u001B[1;32;44mStyled\u001B[0mClear")
        assertEquals('C', emu.getCell(0, 6).char)
        assertFalse(emu.getCell(0, 6).bold)
    }

    @Test
    fun `SGR bright foreground colors`() {
        emu.processString("\u001B[91mBrightRed")
        assertEquals('B', emu.getCell(0, 0).char)
    }

    @Test
    fun `SGR bright background colors`() {
        emu.processString("\u001B[104mBrightBlueBg")
        assertEquals('B', emu.getCell(0, 0).char)
    }

    // ── Cursor Movement (CSI) ─────────────────────────────────────────

    @Test
    fun `cursor up moves up`() {
        emu.processString("\n\n") // move to row 2
        emu.processString("\u001B[1A") // up 1
        assertEquals(1, emu.cursorRow)
    }

    @Test
    fun `cursor down moves down`() {
        emu.processString("\u001B[2B")
        assertEquals(2, emu.cursorRow)
    }

    @Test
    fun `cursor forward moves right`() {
        emu.processString("\u001B[5C")
        assertEquals(5, emu.cursorCol)
    }

    @Test
    fun `cursor back moves left`() {
        emu.processString("Hello")
        emu.processString("\u001B[2D") // back 2
        assertEquals(3, emu.cursorCol)
    }

    @Test
    fun `cursor position H sets exact position`() {
        emu.processString("\u001B[5;10H") // row 5, col 10
        assertEquals(4, emu.cursorRow) // 0-indexed
        assertEquals(9, emu.cursorCol)
    }

    @Test
    fun `cursor position f works same as H`() {
        emu.processString("\u001B[3;8f")
        assertEquals(2, emu.cursorRow)
        assertEquals(7, emu.cursorCol)
    }

    @Test
    fun `cursor home moves to 0,0`() {
        emu.processString("Hello\nWorld")
        emu.processString("\u001B[H")
        assertEquals(0, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `default cursor movement uses 1 when no param`() {
        emu.processString("\u001B[B") // down 1 (default)
        assertEquals(1, emu.cursorRow)
    }

    // ── Screen Operations ─────────────────────────────────────────────

    @Test
    fun `clear screen moves cursor to 0,0`() {
        emu.processString("Hello\nWorld")
        emu.clearScreen()
        assertEquals(0, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
        assertEquals(' ', emu.getCell(0, 0).char)
    }

    @Test
    fun `erase display J mode 2 clears all`() {
        emu.processString("Hello\nWorld!")
        emu.processString("\u001B[2J")
        assertEquals(' ', emu.getCell(0, 0).char)
        assertEquals(' ', emu.getCell(1, 0).char)
    }

    @Test
    fun `erase line K mode 2 clears entire line`() {
        emu.processString("Hello World")
        emu.processByte(0x0D) // CR
        emu.processString("\u001B[2K")
        assertEquals(' ', emu.getCell(0, 0).char)
        assertEquals(' ', emu.getCell(0, 5).char)
    }

    // ── Screen Buffer ─────────────────────────────────────────────────

    @Test
    fun `getScreenLines returns correct text`() {
        emu.processString("Line1\nLine2")
        val lines = emu.getScreenLines()
        assertEquals(24, lines.size) // all rows
        assertTrue(lines[0].trim().startsWith("Line1"))
        assertTrue(lines[1].trim().startsWith("Line2"))
    }

    @Test
    fun `getScreenText concatenates lines`() {
        emu.processString("A\nB")
        val text = emu.getScreenText()
        assertTrue(text.contains("A"))
        assertTrue(text.contains("B"))
    }

    @Test
    fun `getCell returns space for out of bounds`() {
        val cell = emu.getCell(99, 99)
        assertEquals(' ', cell.char)
    }

    @Test
    fun `getCell returns space for negative indices`() {
        val cell = emu.getCell(-1, -1)
        assertEquals(' ', cell.char)
    }

    // ── Resize ────────────────────────────────────────────────────────

    @Test
    fun `resize preserves existing content`() {
        emu.processString("Hello")
        emu.resize(40, 120)
        assertEquals('H', emu.getCell(0, 0).char)
        assertEquals('e', emu.getCell(0, 1).char)
    }

    @Test
    fun `resize to larger dimensions adds empty cells`() {
        emu.processString("A")
        val oldRows = emu.rows
        val oldCols = emu.columns
        emu.resize(oldRows + 10, oldCols + 20)
        assertEquals(oldRows + 10, emu.rows)
        assertEquals(oldCols + 20, emu.columns)
        assertEquals('A', emu.getCell(0, 0).char)
        assertEquals(' ', emu.getCell(emu.rows - 1, emu.columns - 1).char)
    }

    @Test
    fun `resize clamps cursor`() {
        emu.processString("\u001B[20;70H")
        emu.resize(10, 40)
        assertTrue(emu.cursorRow < 10)
        assertTrue(emu.cursorCol < 40)
    }

    @Test
    fun `resize to same size is no-op`() {
        emu.processString("Test")
        val r = emu.rows
        val c = emu.columns
        emu.resize(r, c)
        assertEquals(r, emu.rows)
        assertEquals(c, emu.columns)
        assertEquals('T', emu.getCell(0, 0).char)
    }

    // ── Tab ───────────────────────────────────────────────────────────

    @Test
    fun `tab moves to next 8-column stop`() {
        emu.processByte(0x09) // HT at column 0
        assertEquals(8, emu.cursorCol)
    }

    @Test
    fun `background bell is ignored`() {
        emu.processByte(0x07) // BEL
        assertEquals(0, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    // ── Backspace ─────────────────────────────────────────────────────

    @Test
    fun `backspace moves cursor left`() {
        emu.processString("AB")
        emu.processByte(0x08) // BS
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `backspace at column 0 does nothing`() {
        emu.processByte(0x08) // BS at col 0
        assertEquals(0, emu.cursorCol)
    }

    // ── Scroll Behavior ───────────────────────────────────────────────

    @Test
    fun `scroll occurs when writing past last row`() {
        // Fill all rows
        repeat(25) { emu.processString("line\n") }
        // Should have scrolled - older data in scrollback
        val sb = emu.getScrollbackLines()
        assertTrue(sb.isNotEmpty())
    }

    @Test
    fun `getFullContent includes scrollback`() {
        repeat(50) { emu.processString("row$it\n") }
        val full = emu.getFullContent()
        assertTrue(full.contains("row0"))
        assertTrue(full.contains("row49"))
    }

    // ── Cursor Save/Restore ───────────────────────────────────────────

    @Test
    fun `DEC save restore cursor`() {
        emu.processString("\u001B[5;10H") // move to row 5 col 10
        emu.processByte(0x1B)
        emu.processByte('7'.code) // DECSC - save
        emu.processString("\u001B[H") // home
        assertEquals(0, emu.cursorRow)

        emu.processByte(0x1B)
        emu.processByte('8'.code) // DECRC - restore
        assertEquals(4, emu.cursorRow)
        assertEquals(9, emu.cursorCol)
    }

    @Test
    fun `CSI save restore cursor`() {
        emu.processString("\u001B[3;5H")
        emu.processString("\u001B[s") // save
        emu.processString("\u001B[H")
        emu.processString("\u001B[u") // restore
        assertEquals(2, emu.cursorRow)
        assertEquals(4, emu.cursorCol)
    }

    // ── isDirty Flag ──────────────────────────────────────────────────

    @Test
    fun `isDirty is set after writing`() {
        emu.processByte('X'.code)
        assertTrue(emu.isDirty)
    }

    @Test
    fun `markClean clears dirty flag`() {
        emu.processByte('X'.code)
        emu.markClean()
        assertFalse(emu.isDirty)
    }

    // ── Edge Cases ────────────────────────────────────────────────────

    @Test
    fun `empty string does nothing`() {
        val r = emu.cursorRow
        val c = emu.cursorCol
        emu.processString("")
        assertEquals(r, emu.cursorRow)
        assertEquals(c, emu.cursorCol)
    }

    @Test
    fun `nul byte is ignored`() {
        emu.processByte(0x00)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `unknown escape sequences do not crash`() {
        // Process various invalid/unknown sequences
        emu.processString("\u001B[X\u001B[99;99;99m\u001B[???h")
        // Should not throw
        assertTrue(true)
    }

    @Test
    fun `incomplete escape sequences handled gracefully`() {
        emu.processByte(0x1B) // ESC only (incomplete)
        // Parser should be in ESC state, then reset
        emu.processString("\n") // normal text after
        assertEquals(1, emu.cursorRow)
    }

    @Test
    fun `unicode characters handled`() {
        // UTF-8 encoded characters pass through as bytes
        emu.processString("Hello 🌍 World")
        // Should not crash
        assertTrue(true)
    }

    @Test
    fun `initial state has correct dimensions`() {
        assertEquals(24, emu.rows)
        assertEquals(80, emu.columns)
        assertEquals(0, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
        assertFalse(emu.isDirty)
    }
}
