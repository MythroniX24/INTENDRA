package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * WcWidthTest — comprehensive tests for Unicode character display width.
 *
 * WcWidth.charWidth(codePoint) returns the number of terminal columns a
 * Unicode code point occupies:
 * - 0 for combining characters and control chars
 * - 1 for regular characters (ASCII, Latin, etc.)
 * - 2 for wide characters (CJK ideographs, emoji, etc.)
 */
class WcWidthTest {

    // ── ASCII (width = 1) ───────────────────────────────────────────

    @Test
    fun `space has width 1`() {
        assertEquals(1, WcWidth.charWidth(' '.code))
    }

    @Test
    fun `ASCII letters have width 1`() {
        assertEquals(1, WcWidth.charWidth('A'.code))
        assertEquals(1, WcWidth.charWidth('z'.code))
        assertEquals(1, WcWidth.charWidth('M'.code))
    }

    @Test
    fun `ASCII digits have width 1`() {
        assertEquals(1, WcWidth.charWidth('0'.code))
        assertEquals(1, WcWidth.charWidth('5'.code))
        assertEquals(1, WcWidth.charWidth('9'.code))
    }

    @Test
    fun `ASCII punctuation has width 1`() {
        assertEquals(1, WcWidth.charWidth('!'.code))
        assertEquals(1, WcWidth.charWidth('.'.code))
        assertEquals(1, WcWidth.charWidth('?'.code))
        assertEquals(1, WcWidth.charWidth('~'.code))
        assertEquals(1, WcWidth.charWidth('@'.code))
        assertEquals(1, WcWidth.charWidth('#'.code))
    }

    @Test
    fun `ASCII control chars 0x00 to 0x1F have width 0`() {
        for (cp in 0x00..0x1F) {
            assertEquals("0x${cp.toString(16)} should have width 0", 0, WcWidth.charWidth(cp))
        }
    }

    @Test
    fun `DEL has width 0`() {
        assertEquals(0, WcWidth.charWidth(0x7F))
    }

    // ── Extended Latin (width = 1) ─────────────────────────────────

    @Test
    fun `Latin-1 supplement has width 1`() {
        assertEquals(1, WcWidth.charWidth(0x00C0)) // À
        assertEquals(1, WcWidth.charWidth(0x00E9)) // é
        assertEquals(1, WcWidth.charWidth(0x00FF)) // ÿ
    }

    @Test
    fun `Latin Extended has width 1`() {
        assertEquals(1, WcWidth.charWidth(0x0100)) // Ā
        assertEquals(1, WcWidth.charWidth(0x024F)) // ɏ
    }

    // ── CJK Ideographs (width = 2) ─────────────────────────────────

    @Test
    fun `CJK unified ideographs have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x4E00)) // 一
        assertEquals(2, WcWidth.charWidth(0x6C49)) // 汉
        assertEquals(2, WcWidth.charWidth(0x6C34)) // 水
        assertEquals(2, WcWidth.charWidth(0x9FFF))
    }

    @Test
    fun `CJK Extension A has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x3400))
        assertEquals(2, WcWidth.charWidth(0x4DBF))
    }

    @Test
    fun `CJK Compatibility Ideographs have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xF900))
        assertEquals(2, WcWidth.charWidth(0xFAFF))
    }

    // ── CJK Symbols and Punctuation (width = 2) ────────────────────

    @Test
    fun `CJK symbols and punctuation have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x3000)) // Ideographic space
        assertEquals(2, WcWidth.charWidth(0x3001)) // 、
        assertEquals(2, WcWidth.charWidth(0x3002)) // 。
        assertEquals(2, WcWidth.charWidth(0xFF01)) // ！
        assertEquals(2, WcWidth.charWidth(0xFF05)) // ％
        assertEquals(2, WcWidth.charWidth(0xFF5E)) // ～
    }

    @Test
    fun `full-width ASCII variants have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xFF01)) // ！
        assertEquals(2, WcWidth.charWidth(0xFF21)) // Ａ
        assertEquals(2, WcWidth.charWidth(0xFF41)) // ａ
    }

    // ── Hangul (width = 2) ─────────────────────────────────────────

    @Test
    fun `Hangul Jamo has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1100))
        assertEquals(2, WcWidth.charWidth(0x115F))
    }

    @Test
    fun `Hangul Syllables have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xAC00)) // 가
        assertEquals(2, WcWidth.charWidth(0xD7A3)) // 힣
    }

    // ── Emoji (width = 2 where applicable) ──────────────────────────

    @Test
    fun `emoji symbols have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1F600)) // 😀
        assertEquals(2, WcWidth.charWidth(0x1F44D)) // 👍
        assertEquals(2, WcWidth.charWidth(0x1F680)) // 🚀
        assertEquals(2, WcWidth.charWidth(0x1F355)) // 🍕
    }

    @Test
    fun `flags and complex emoji have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1F1E6)) // Regional indicator A
        assertEquals(2, WcWidth.charWidth(0x1F3F4)) // Waving black flag
    }

    @Test
    fun `dingbats and symbols have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x2700)) // ✀
        assertEquals(1, WcWidth.charWidth(0x2764)) // ❤
    }

    // ── Combining Characters (width = 0) ────────────────────────────

    @Test
    fun `combining grave accent has width 0`() {
        assertEquals(0, WcWidth.charWidth(0x0300))
    }

    @Test
    fun `combining acute accent has width 0`() {
        assertEquals(0, WcWidth.charWidth(0x0301))
    }

    @Test
    fun `Cyrillic combining marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0x0483))
    }

    @Test
    fun `hebrew combining marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0x0591))
        assertEquals(0, WcWidth.charWidth(0x05BD))
    }

    @Test
    fun `arabic combining marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0x064B))
        assertEquals(0, WcWidth.charWidth(0x0655))
    }

    @Test
    fun `variation selectors have width 0`() {
        assertEquals(0, WcWidth.charWidth(0xFE00))
        assertEquals(0, WcWidth.charWidth(0xFE0F))
    }

    @Test
    fun `combining half marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0xFE20))
        assertEquals(0, WcWidth.charWidth(0xFE23))
    }

    @Test
    fun `enclosing combining marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0x20DD))
        assertEquals(0, WcWidth.charWidth(0x20E3))
    }

    // ── Zero Width Characters ───────────────────────────────────────

    @Test
    fun `zero width space has width 0`() {
        assertEquals(0, WcWidth.charWidth(0x200B))
    }

    @Test
    fun `zero width joiner has width 0`() {
        assertEquals(0, WcWidth.charWidth(0x200D))
    }

    @Test
    fun `zero width non-joiner has width 0`() {
        assertEquals(0, WcWidth.charWidth(0x200C))
    }

    // ── Misc Ranges (width = 1) ─────────────────────────────────────

    @Test
    fun `greek letters have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x03B1)) // α
        assertEquals(1, WcWidth.charWidth(0x03C9)) // ω
    }

    @Test
    fun `cyrillic letters have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x0410)) // А
        assertEquals(1, WcWidth.charWidth(0x044F)) // я
    }

    @Test
    fun `arabic letters have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x0627)) // ا
        assertEquals(1, WcWidth.charWidth(0x064A)) // ي
    }

    // ── Special Ranges (width = 2) ──────────────────────────────────

    @Test
    fun `CJK radical supplement has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x2E80))
        assertEquals(2, WcWidth.charWidth(0x2EF3))
    }

    @Test
    fun `Kangxi radicals have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x2F00))
        assertEquals(2, WcWidth.charWidth(0x2FD5))
    }

    @Test
    fun `CJK Strokes have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x31C0))
        assertEquals(2, WcWidth.charWidth(0x31EF))
    }

    @Test
    fun `Enclosed CJK letters months have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x3200))
        assertEquals(2, WcWidth.charWidth(0x32FE))
    }

    @Test
    fun `CJK compatibility have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x3300))
        assertEquals(2, WcWidth.charWidth(0x33FF))
    }

    @Test
    fun `Yi syllables and Yi radicals have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xA000))
        assertEquals(2, WcWidth.charWidth(0xA4CF))
    }

    // ── Edge Cases ──────────────────────────────────────────────────

    @Test
    fun `negative codePoint returns 1`() {
        assertEquals(1, WcWidth.charWidth(-1))
        assertEquals(1, WcWidth.charWidth(-100))
    }

    @Test
    fun `codePoint above Unicode max returns 1`() {
        assertEquals(1, WcWidth.charWidth(0x200000))
        assertEquals(1, WcWidth.charWidth(0x10FFFF))
    }

    @Test
    fun `soft hyphen has width 1`() {
        assertEquals(1, WcWidth.charWidth(0x00AD))
    }

    // ── Unicode Blocks at Boundaries ─────────────────────────────────

    @Test
    fun `characters just before CJK range have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x2FFE))
        assertEquals(1, WcWidth.charWidth(0x2FFF))
    }

    @Test
    fun `character just after CJK compat have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x33FF))
    }

    @Test
    fun `character at CJK boundary is width 2`() {
        assertEquals(2, WcWidth.charWidth(0x3400))
        assertEquals(2, WcWidth.charWidth(0x4DBF))
    }

    // ── Common Terminal Characters ──────────────────────────────────

    @Test
    fun `box drawing chars have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x2500)) // ─
        assertEquals(1, WcWidth.charWidth(0x2502)) // │
        assertEquals(1, WcWidth.charWidth(0x251C)) // ├
        assertEquals(1, WcWidth.charWidth(0x2524)) // ┤
        assertEquals(1, WcWidth.charWidth(0x2563)) // ╣
    }

    @Test
    fun `block elements have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x2580)) // ▀
        assertEquals(1, WcWidth.charWidth(0x2588)) // █
        assertEquals(1, WcWidth.charWidth(0x2592)) // ▒
        assertEquals(1, WcWidth.charWidth(0x2593)) // ▓
    }

    @Test
    fun `geometric shapes have width 1`() {
        assertEquals(1, WcWidth.charWidth(0x25A0)) // ■
        assertEquals(1, WcWidth.charWidth(0x25CB)) // ○
        assertEquals(1, WcWidth.charWidth(0x25C6)) // ◆
        assertEquals(1, WcWidth.charWidth(0x25BA)) // ►
    }

    @Test
    fun `currency symbols have width 1`() {
        assertEquals(1, WcWidth.charWidth('$'.code))
        assertEquals(1, WcWidth.charWidth('€'.code))
        assertEquals(1, WcWidth.charWidth('¥'.code))
        assertEquals(1, WcWidth.charWidth('£'.code))
    }

    // ── Char overload ───────────────────────────────────────────────

    @Test
    fun `charWidth with Char argument works`() {
        assertEquals(1, WcWidth.charWidth('A'))
        assertEquals(1, WcWidth.charWidth(' '))
        assertEquals(1, WcWidth.charWidth('1'))
        assertEquals(2, WcWidth.charWidth('一')) // CJK char within BMP
    }
}
