package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * TextStyleTest — comprehensive tests for the Int-bitfield style encoding.
 *
 * TextStyle packs terminal text attributes (foreground, background, bold,
 * italic, underline, blink, reverse, strikethrough, dim, hidden) into a
 * single Int, which makes per-cell style storage very space-efficient.
 *
 * ## Bit Layout
 * ```
 * Bits  0-7:  foreground (0-255)
 * Bits  8-15: background (0-255)
 * Bit  16:    bold
 * Bit  17:    dim
 * Bit  18:    italic
 * Bit  19:    underline
 * Bit  20:    blink
 * Bit  21:    reverse
 * Bit  22:    strikethrough
 * Bit  23:    hidden
 * ```
 */
class TextStyleTest {

    // ── Default ──────────────────────────────────────────────────────

    @Test
    fun `default style has default foreground and background`() {
        assertEquals(TerminalEmulator.COLOR_DEFAULT_FG, TextStyle.foreground(TextStyle.DEFAULT))
        assertEquals(TerminalEmulator.COLOR_DEFAULT_BG, TextStyle.background(TextStyle.DEFAULT))
    }

    @Test
    fun `default style has no attributes set`() {
        assertFalse(TextStyle.bold(TextStyle.DEFAULT))
        assertFalse(TextStyle.dim(TextStyle.DEFAULT))
        assertFalse(TextStyle.italic(TextStyle.DEFAULT))
        assertFalse(TextStyle.underline(TextStyle.DEFAULT))
        assertFalse(TextStyle.blink(TextStyle.DEFAULT))
        assertFalse(TextStyle.reverse(TextStyle.DEFAULT))
        assertFalse(TextStyle.strikethrough(TextStyle.DEFAULT))
        assertFalse(TextStyle.hidden(TextStyle.DEFAULT))
    }

    // ── Foreground colors ───────────────────────────────────────────

    @Test
    fun `encode sets foreground color correctly`() {
        val encoded = TextStyle.encode(foreground = 2) // green
        assertEquals(2, TextStyle.foreground(encoded))
    }

    @Test
    fun `encode preserves foreground 0 (black)`() {
        val encoded = TextStyle.encode(foreground = 0)
        assertEquals(0, TextStyle.foreground(encoded))
    }

    @Test
    fun `encode preserves foreground 255 (max 8-bit)`() {
        val encoded = TextStyle.encode(foreground = 255)
        assertEquals(255, TextStyle.foreground(encoded))
    }

    @Test
    fun `encode clamps foreground to 0-255`() {
        // encode only stores the low 8 bits
        val encoded = TextStyle.encode(foreground = 300) // 300 & 0xFF = 44
        assertEquals(44, TextStyle.foreground(encoded))
    }

    @Test
    fun `encode clamps negative foreground to 0-255`() {
        val encoded = TextStyle.encode(foreground = -1) // -1 & 0xFF = 255
        assertEquals(255, TextStyle.foreground(encoded))
    }

    // ── Background colors ───────────────────────────────────────────

    @Test
    fun `encode sets background color correctly`() {
        val encoded = TextStyle.encode(background = 4) // blue
        assertEquals(4, TextStyle.background(encoded))
    }

    @Test
    fun `encode preserves background 0 (black)`() {
        val encoded = TextStyle.encode(background = 0)
        assertEquals(0, TextStyle.background(encoded))
    }

    @Test
    fun `background and foreground are independent`() {
        val encoded = TextStyle.encode(foreground = 2, background = 4)
        assertEquals(2, TextStyle.foreground(encoded))
        assertEquals(4, TextStyle.background(encoded))
    }

    @Test
    fun `swap foreground and background produces different styles`() {
        val style1 = TextStyle.encode(foreground = 1, background = 2)
        val style2 = TextStyle.encode(foreground = 2, background = 1)
        assertNotEquals(style1, style2)
        assertEquals(1, TextStyle.foreground(style1))
        assertEquals(2, TextStyle.background(style1))
        assertEquals(2, TextStyle.foreground(style2))
        assertEquals(1, TextStyle.background(style2))
    }

    // ── Bold ────────────────────────────────────────────────────────

    @Test
    fun `bold attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(bold = true)
        assertTrue(TextStyle.bold(encoded))
    }

    @Test
    fun `bold false returns false`() {
        val encoded = TextStyle.encode(bold = false)
        assertFalse(TextStyle.bold(encoded))
    }

    @Test
    fun `bold combined with color`() {
        val encoded = TextStyle.encode(foreground = 1, bold = true)
        assertTrue(TextStyle.bold(encoded))
        assertEquals(1, TextStyle.foreground(encoded))
    }

    // ── Dim ─────────────────────────────────────────────────────────

    @Test
    fun `dim attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(dim = true)
        assertTrue(TextStyle.dim(encoded))
    }

    @Test
    fun `dim false returns false`() {
        val encoded = TextStyle.encode(dim = false)
        assertFalse(TextStyle.dim(encoded))
    }

    // ── Italic ──────────────────────────────────────────────────────

    @Test
    fun `italic attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(italic = true)
        assertTrue(TextStyle.italic(encoded))
    }

    @Test
    fun `italic false returns false`() {
        val encoded = TextStyle.encode(italic = false)
        assertFalse(TextStyle.italic(encoded))
    }

    // ── Underline ───────────────────────────────────────────────────

    @Test
    fun `underline attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(underline = true)
        assertTrue(TextStyle.underline(encoded))
    }

    @Test
    fun `underline false returns false`() {
        val encoded = TextStyle.encode(underline = false)
        assertFalse(TextStyle.underline(encoded))
    }

    // ── Blink ───────────────────────────────────────────────────────

    @Test
    fun `blink attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(blink = true)
        assertTrue(TextStyle.blink(encoded))
    }

    @Test
    fun `blink false returns false`() {
        val encoded = TextStyle.encode(blink = false)
        assertFalse(TextStyle.blink(encoded))
    }

    // ── Reverse ─────────────────────────────────────────────────────

    @Test
    fun `reverse attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(reverse = true)
        assertTrue(TextStyle.reverse(encoded))
    }

    @Test
    fun `reverse false returns false`() {
        val encoded = TextStyle.encode(reverse = false)
        assertFalse(TextStyle.reverse(encoded))
    }

    // ── Strikethrough ───────────────────────────────────────────────

    @Test
    fun `strikethrough attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(strikethrough = true)
        assertTrue(TextStyle.strikethrough(encoded))
    }

    @Test
    fun `strikethrough false returns false`() {
        val encoded = TextStyle.encode(strikethrough = false)
        assertFalse(TextStyle.strikethrough(encoded))
    }

    // ── Hidden ──────────────────────────────────────────────────────

    @Test
    fun `hidden attribute is encoded and decoded`() {
        val encoded = TextStyle.encode(hidden = true)
        assertTrue(TextStyle.hidden(encoded))
    }

    @Test
    fun `hidden false returns false`() {
        val encoded = TextStyle.encode(hidden = false)
        assertFalse(TextStyle.hidden(encoded))
    }

    // ── Multiple attributes combined ────────────────────────────────

    @Test
    fun `all attributes combined produce correct style`() {
        val encoded = TextStyle.encode(
            foreground = 2, background = 4,
            bold = true, dim = true, italic = true,
            underline = true, blink = true, reverse = true,
            strikethrough = true, hidden = true
        )
        assertEquals(2, TextStyle.foreground(encoded))
        assertEquals(4, TextStyle.background(encoded))
        assertTrue(TextStyle.bold(encoded))
        assertTrue(TextStyle.dim(encoded))
        assertTrue(TextStyle.italic(encoded))
        assertTrue(TextStyle.underline(encoded))
        assertTrue(TextStyle.blink(encoded))
        assertTrue(TextStyle.reverse(encoded))
        assertTrue(TextStyle.strikethrough(encoded))
        assertTrue(TextStyle.hidden(encoded))
    }

    @Test
    fun `all attributes false means no bits set`() {
        val encoded = TextStyle.encode(
            foreground = 7, background = 0,
            bold = false, dim = false, italic = false,
            underline = false, blink = false, reverse = false,
            strikethrough = false, hidden = false
        )
        assertEquals(7, TextStyle.foreground(encoded))
        assertEquals(0, TextStyle.background(encoded))
        assertFalse(TextStyle.bold(encoded))
        assertFalse(TextStyle.dim(encoded))
        assertFalse(TextStyle.italic(encoded))
        assertFalse(TextStyle.underline(encoded))
        assertFalse(TextStyle.blink(encoded))
        assertFalse(TextStyle.reverse(encoded))
        assertFalse(TextStyle.strikethrough(encoded))
        assertFalse(TextStyle.hidden(encoded))
    }

    @Test
    fun `bold and italic together preserve both`() {
        val encoded = TextStyle.encode(bold = true, italic = true)
        assertTrue(TextStyle.bold(encoded))
        assertTrue(TextStyle.italic(encoded))
    }

    @Test
    fun `underline and strikethrough together preserve both`() {
        val encoded = TextStyle.encode(underline = true, strikethrough = true)
        assertTrue(TextStyle.underline(encoded))
        assertTrue(TextStyle.strikethrough(encoded))
    }

    @Test
    fun `reverse and hidden together preserve both`() {
        val encoded = TextStyle.encode(reverse = true, hidden = true)
        assertTrue(TextStyle.reverse(encoded))
        assertTrue(TextStyle.hidden(encoded))
    }

    // ── Roundtrip ───────────────────────────────────────────────────

    @Test
    fun `encode decode roundtrip preserves all fields`() {
        val testCases = listOf(
            TextStyle.encode(),
            TextStyle.encode(foreground = 3, background = 5),
            TextStyle.encode(bold = true, italic = true, underline = true),
            TextStyle.encode(foreground = 1, bold = true, blink = true, reverse = true),
            TextStyle.encode(
                foreground = 7, background = 0,
                bold = true, dim = false, italic = true,
                underline = false, blink = true, reverse = false,
                strikethrough = true, hidden = false
            )
        )
        for (encoded in testCases) {
            // Re-encode and verify bits don't change
            val decoded = TextStyle.encode(
                foreground = TextStyle.foreground(encoded),
                background = TextStyle.background(encoded),
                bold = TextStyle.bold(encoded),
                dim = TextStyle.dim(encoded),
                italic = TextStyle.italic(encoded),
                underline = TextStyle.underline(encoded),
                blink = TextStyle.blink(encoded),
                reverse = TextStyle.reverse(encoded),
                strikethrough = TextStyle.strikethrough(encoded),
                hidden = TextStyle.hidden(encoded)
            )
            assertEquals(encoded, decoded)
        }
    }

    @Test
    fun `bitfields do not overlap`() {
        // Test that setting foreground doesn't affect bg and vice versa
        val fgOnly = TextStyle.encode(foreground = 0xFF)
        val bgOnly = TextStyle.encode(background = 0xFF)

        assertEquals(0xFF, TextStyle.foreground(fgOnly))
        assertEquals(TerminalEmulator.COLOR_DEFAULT_BG, TextStyle.background(fgOnly))

        assertEquals(TerminalEmulator.COLOR_DEFAULT_FG, TextStyle.foreground(bgOnly))
        assertEquals(0xFF, TextStyle.background(bgOnly))
    }

    // ── Edge Cases ──────────────────────────────────────────────────

    @Test
    fun `each attribute bit is independent`() {
        // Test each attribute one at a time to ensure no bit spillage
        val attrs = listOf(
            "bold" to { s: Int -> TextStyle.bold(s) },
            "dim" to { s: Int -> TextStyle.dim(s) },
            "italic" to { s: Int -> TextStyle.italic(s) },
            "underline" to { s: Int -> TextStyle.underline(s) },
            "blink" to { s: Int -> TextStyle.blink(s) },
            "reverse" to { s: Int -> TextStyle.reverse(s) },
            "strikethrough" to { s: Int -> TextStyle.strikethrough(s) },
            "hidden" to { s: Int -> TextStyle.hidden(s) }
        )
        for ((name, getter) in attrs) {
            val encoded = TextStyle.encode(
                foreground = 3, background = 5,
                bold = name == "bold", dim = name == "dim",
                italic = name == "italic", underline = name == "underline",
                blink = name == "blink", reverse = name == "reverse",
                strikethrough = name == "strikethrough", hidden = name == "hidden"
            )
            for ((otherName, otherGetter) in attrs) {
                if (name == otherName) assertTrue("$name should be true", otherGetter(encoded))
                else assertFalse("$otherName should be false for $name", otherGetter(encoded))
            }
            assertEquals("foreground not preserved for $name", 3, TextStyle.foreground(encoded))
            assertEquals("background not preserved for $name", 5, TextStyle.background(encoded))
        }
    }

    @Test
    fun `style equality with same attributes`() {
        val a = TextStyle.encode(foreground = 2, background = 4, bold = true, italic = true)
        val b = TextStyle.encode(foreground = 2, background = 4, bold = true, italic = true)
        assertEquals(a, b)
    }

    @Test
    fun `style inequality with different attributes`() {
        val a = TextStyle.encode(foreground = 2, background = 4, bold = true)
        val b = TextStyle.encode(foreground = 2, background = 4, bold = false)
        assertNotEquals(a, b)
    }
}
