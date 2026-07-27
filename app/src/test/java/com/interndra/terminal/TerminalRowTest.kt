package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * TerminalRowTest — comprehensive tests for a single terminal screen row.
 *
 * TerminalRow holds the character data and style data for each column
 * in a row of the terminal screen buffer. It also tracks true-color
 * overrides and the "wrapped" flag for line wrapping.
 */
class TerminalRowTest {

    // ── Initial State ────────────────────────────────────────────────

    @Test
    fun `new row has correct number of columns`() {
        val row = TerminalRow(80)
        assertEquals(80, row.columns)
    }

    @Test
    fun `new row is filled with spaces`() {
        val row = TerminalRow(80)
        for (c in 0 until 80) {
            assertEquals(' ', row.getChar(c))
        }
    }

    @Test
    fun `new row has default styles`() {
        val row = TerminalRow(80)
        for (c in 0 until 80) {
            assertEquals(TextStyle.DEFAULT, row.getStyle(c))
        }
    }

    @Test
    fun `new row has default true color`() {
        val row = TerminalRow(80)
        for (c in 0 until 80) {
            assertEquals(0, row.trueColorFg[c])
            assertEquals(0, row.trueColorBg[c])
        }
    }

    @Test
    fun `new row is not wrapped`() {
        val row = TerminalRow(80)
        assertFalse(row.isWrapped)
    }

    @Test
    fun `text property returns row content`() {
        val row = TerminalRow(80)
        assertEquals(80, row.text.length)
        assertEquals(" ".repeat(80), row.text)
    }

    // ── SetCell / GetChar / GetStyle ─────────────────────────────────

    @Test
    fun `setChar stores character at column`() {
        val row = TerminalRow(80)
        row.setCell(5, 'X', TextStyle.encode(foreground = 2))
        assertEquals('X', row.getChar(5))
    }

    @Test
    fun `setChar stores style at column`() {
        val row = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2, bold = true)
        row.setCell(3, 'A', style)
        assertEquals(style, row.getStyle(3))
    }

    @Test
    fun `multiple cells set independently`() {
        val row = TerminalRow(80)
        val styleA = TextStyle.encode(foreground = 1)
        val styleB = TextStyle.encode(foreground = 2)
        row.setCell(0, 'A', styleA)
        row.setCell(1, 'B', styleB)
        assertEquals('A', row.getChar(0))
        assertEquals(styleA, row.getStyle(0))
        assertEquals('B', row.getChar(1))
        assertEquals(styleB, row.getStyle(1))
    }

    @Test
    fun `overwriting cell replaces old values`() {
        val row = TerminalRow(80)
        val oldStyle = TextStyle.encode(foreground = 1)
        val newStyle = TextStyle.encode(foreground = 2)
        row.setCell(0, 'A', oldStyle)
        row.setCell(0, 'B', newStyle)
        assertEquals('B', row.getChar(0))
        assertEquals(newStyle, row.getStyle(0))
    }

    @Test
    fun `setChar with negative col does nothing`() {
        val row = TerminalRow(80)
        row.setCell(-1, 'X', TextStyle.DEFAULT)
        assertEquals(' ', row.getChar(0))
    }

    @Test
    fun `setChar with col beyond bounds does nothing`() {
        val row = TerminalRow(80)
        row.setCell(80, 'X', TextStyle.DEFAULT)
        row.setCell(200, 'Y', TextStyle.DEFAULT)
        assertEquals(' ', row.getChar(79))
    }

    @Test
    fun `getChar with negative col returns space`() {
        val row = TerminalRow(80)
        row.setCell(0, 'A', TextStyle.DEFAULT)
        assertEquals(' ', row.getChar(-1))
    }

    @Test
    fun `getChar with col beyond bounds returns space`() {
        val row = TerminalRow(80)
        assertEquals(' ', row.getChar(80))
        assertEquals(' ', row.getChar(1000))
    }

    @Test
    fun `getStyle with negative col returns default`() {
        val row = TerminalRow(80)
        assertEquals(TextStyle.DEFAULT, row.getStyle(-1))
    }

    @Test
    fun `getStyle with col beyond bounds returns default`() {
        val row = TerminalRow(80)
        assertEquals(TextStyle.DEFAULT, row.getStyle(80))
        assertEquals(TextStyle.DEFAULT, row.getStyle(1000))
    }

    // ── True Color ──────────────────────────────────────────────────

    @Test
    fun `setTrueColorFg sets RGB value`() {
        val row = TerminalRow(80)
        row.setTrueColorFg(5, 0xFF8800)
        assertEquals(0xFF8800, row.trueColorFg[5])
    }

    @Test
    fun `setTrueColorBg sets RGB value`() {
        val row = TerminalRow(80)
        row.setTrueColorBg(3, 0x0044AA)
        assertEquals(0x0044AA, row.trueColorBg[3])
    }

    @Test
    fun `trueColorFg with negative col does nothing`() {
        val row = TerminalRow(80)
        row.setTrueColorFg(-1, 0xFF0000)
        assertEquals(0, row.trueColorFg[0])
    }

    // ── Clear ────────────────────────────────────────────────────────

    @Test
    fun `clear resets all characters to spaces`() {
        val row = TerminalRow(80)
        row.setCell(0, 'X', TextStyle.encode(foreground = 1))
        row.setCell(40, 'Y', TextStyle.encode(foreground = 2))
        row.clear()
        for (c in 0 until 80) {
            assertEquals(' ', row.getChar(c))
        }
    }

    @Test
    fun `clear resets all styles to default`() {
        val row = TerminalRow(80)
        row.setCell(0, 'X', TextStyle.encode(foreground = 1, bold = true))
        row.clear()
        for (c in 0 until 80) {
            assertEquals(TextStyle.DEFAULT, row.getStyle(c))
        }
    }

    @Test
    fun `clear resets true color arrays`() {
        val row = TerminalRow(80)
        row.setTrueColorFg(0, 0xFF0000)
        row.setTrueColorBg(0, 0x00FF00)
        row.clear()
        assertEquals(0, row.trueColorFg[0])
        assertEquals(0, row.trueColorBg[0])
    }

    @Test
    fun `clear resets isWrapped`() {
        val row = TerminalRow(80)
        row.markWrapped()
        assertTrue(row.isWrapped)
        row.clear()
        assertFalse(row.isWrapped)
    }

    // ── Wrap State ──────────────────────────────────────────────────

    @Test
    fun `markWrapped sets wrapped to true`() {
        val row = TerminalRow(80)
        assertFalse(row.isWrapped)
        row.markWrapped()
        assertTrue(row.isWrapped)
    }

    @Test
    fun `markWrapped multiple times stays true`() {
        val row = TerminalRow(80)
        row.markWrapped()
        row.markWrapped()
        assertTrue(row.isWrapped)
    }

    // ── CopyFrom ────────────────────────────────────────────────────

    @Test
    fun `copyFrom copies all characters`() {
        val src = TerminalRow(80)
        val dst = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        src.setCell(0, 'X', style)
        src.setCell(79, 'Z', style)
        dst.copyFrom(src)
        assertEquals('X', dst.getChar(0))
        assertEquals('Z', dst.getChar(79))
        assertEquals(style, dst.getStyle(0))
        assertEquals(style, dst.getStyle(79))
    }

    @Test
    fun `copyFrom preserves isWrapped`() {
        val src = TerminalRow(80)
        src.markWrapped()
        val dst = TerminalRow(80)
        dst.copyFrom(src)
        assertTrue(dst.isWrapped)
    }

    @Test
    fun `copyFrom preserves true colors`() {
        val src = TerminalRow(80)
        src.setTrueColorFg(5, 0xFF8800)
        src.setTrueColorBg(5, 0x0044AA)
        val dst = TerminalRow(80)
        dst.copyFrom(src)
        assertEquals(0xFF8800, dst.trueColorFg[5])
        assertEquals(0x0044AA, dst.trueColorBg[5])
    }

    @Test
    fun `copyFrom to smaller row copies min columns`() {
        val src = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        src.setCell(79, 'X', style)
        val dst = TerminalRow(40)
        dst.copyFrom(src)
        assertEquals(' ', dst.getChar(39)) // column 39 not set in src and within dst bounds
        // Only min(80,40)=40 columns should be copied
    }

    @Test
    fun `copyFrom to larger row leaves extra columns unchanged`() {
        val src = TerminalRow(40)
        val style = TextStyle.encode(foreground = 2)
        src.setCell(39, 'A', style)
        val dst = TerminalRow(80)
        // Fill dst with something different
        dst.setCell(50, 'B', style)
        dst.copyFrom(src)
        assertEquals('A', dst.getChar(39))
        // Column 50 was set before copy and shouldn't be overwritten since
        // copyFrom only copies min(40,80)=40 columns
        assertEquals('B', dst.getChar(50))
    }

    @Test
    fun `copyFrom does not affect src`() {
        val src = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        src.setCell(0, 'X', style)
        src.markWrapped()
        val dst = TerminalRow(80)
        dst.copyFrom(src)
        dst.setCell(0, 'Y', TextStyle.encode(foreground = 3))
        // src should be unchanged
        assertEquals('X', src.getChar(0))
        assertEquals(style, src.getStyle(0))
    }

    // ── CloneRow ────────────────────────────────────────────────────

    @Test
    fun `cloneRow creates independent copy`() {
        val original = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        original.setCell(0, 'X', style)
        original.markWrapped()
        original.setTrueColorFg(5, 0xFF8800)

        val clone = original.cloneRow()
        // Modify original
        original.setCell(0, 'Y', TextStyle.encode(foreground = 3))
        // Clone should be unaffected
        assertEquals('X', clone.getChar(0))
        assertEquals(style, clone.getStyle(0))
        assertTrue(clone.isWrapped)
        assertEquals(0xFF8800, clone.trueColorFg[5])
    }

    @Test
    fun `cloneRow has correct dimensions`() {
        val original = TerminalRow(120)
        val clone = original.cloneRow()
        assertEquals(120, clone.columns)
    }

    @Test
    fun `cloneRow copies all 80 columns`() {
        val original = TerminalRow(80)
        val style = TextStyle.encode(foreground = 7)
        for (c in 0 until 80) {
            original.setCell(c, 'A', style)
        }
        val clone = original.cloneRow()
        for (c in 0 until 80) {
            assertEquals('A', clone.getChar(c))
        }
    }

    // ── Single-char setters ─────────────────────────────────────────

    @Test
    fun `setChar sets char without changing style`() {
        val row = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        row.setCell(0, 'A', style)
        row.setChar(0, 'B')
        assertEquals('B', row.getChar(0))
        // Style should remain the same
        assertEquals(style, row.getStyle(0))
    }

    @Test
    fun `setChar with invalid index does nothing`() {
        val row = TerminalRow(80)
        row.setCell(0, 'A', TextStyle.DEFAULT)
        row.setChar(-1, 'B')
        row.setChar(80, 'C')
        assertEquals('A', row.getChar(0))
    }

    @Test
    fun `setStyle sets style without changing char`() {
        val row = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        row.setCell(0, 'A', TextStyle.DEFAULT)
        row.setStyle(0, style)
        assertEquals('A', row.getChar(0))
        assertEquals(style, row.getStyle(0))
    }

    @Test
    fun `setStyle with invalid index does nothing`() {
        val row = TerminalRow(80)
        val style = TextStyle.encode(foreground = 2)
        row.setCell(0, 'A', TextStyle.DEFAULT)
        row.setStyle(-1, style)
        row.setStyle(80, style)
        assertEquals(TextStyle.DEFAULT, row.getStyle(0))
    }

    // ── Edge Cases ──────────────────────────────────────────────────

    @Test
    fun `row of size 0 can be created`() {
        val row = TerminalRow(0)
        assertEquals(0, row.columns)
        assertEquals("", row.text)
    }

    @Test
    fun `row of size 1`() {
        val row = TerminalRow(1)
        assertEquals(1, row.columns)
        assertEquals(' ', row.getChar(0))
        row.setCell(0, 'A', TextStyle.encode(foreground = 1))
        assertEquals('A', row.getChar(0))
    }

    @Test
    fun `row of size 200 handles many columns`() {
        val row = TerminalRow(200)
        val style = TextStyle.encode(foreground = 7)
        for (c in 0 until 200) {
            row.setCell(c, 'X', style)
        }
        for (c in 0 until 200) {
            assertEquals('X', row.getChar(c))
        }
    }

    @Test
    fun `toString returns row text`() {
        val row = TerminalRow(5)
        row.setCell(0, 'H', TextStyle.DEFAULT)
        row.setCell(1, 'e', TextStyle.DEFAULT)
        row.setCell(2, 'l', TextStyle.DEFAULT)
        row.setCell(3, 'l', TextStyle.DEFAULT)
        row.setCell(4, 'o', TextStyle.DEFAULT)
        assertEquals("Hello", row.toString().trim()) // spaces after Hello
        assertTrue(row.toString().startsWith("Hello"))
    }

    @Test
    fun `copyFrom with same row handles self-copy`() {
        val row = TerminalRow(10)
        val style = TextStyle.encode(foreground = 2)
        row.setCell(0, 'A', style)
        row.setCell(1, 'B', style)
        row.markWrapped()
        // Self-copy should work without crashing
        row.copyFrom(row)
        assertEquals('A', row.getChar(0))
        assertEquals('B', row.getChar(1))
        assertTrue(row.isWrapped)
    }
}
