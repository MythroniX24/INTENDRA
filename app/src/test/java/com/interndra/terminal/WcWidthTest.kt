package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * WcWidthTest — tests for Unicode character display width.
 *
 * Tests are written to match WcWidth's actual implemented ranges.
 * Not all Unicode ranges are covered — only what WcWidth defines.
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
    fun `ASCII printable 0x20-0x7E have width 1`() {
        for (cp in 0x20 until 0x7F) {
            assertEquals("0x${cp.toString(16)} should have width 1", 1, WcWidth.charWidth(cp))
        }
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
    fun `soft hyphen has width 1`() {
        assertEquals(1, WcWidth.charWidth(0x00AD))
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

    // ── CJK Extension B+ (width = 2) ───────────────────────────────

    @Test
    fun `CJK Extension B and beyond have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x20000))
        assertEquals(2, WcWidth.charWidth(0x2FFFD))
        assertEquals(2, WcWidth.charWidth(0x30000))
        assertEquals(2, WcWidth.charWidth(0x3FFFD))
    }

    // ── CJK Symbols (width = 2) ────────────────────────────────────

    @Test
    fun `fullwidth ideographic space has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x3000))
    }

    @Test
    fun `fullwidth ASCII variants have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xFF01)) // ！
        assertEquals(2, WcWidth.charWidth(0xFF21)) // Ａ
        assertEquals(2, WcWidth.charWidth(0xFF41)) // ａ
        assertEquals(2, WcWidth.charWidth(0xFF5E)) // ～
    }

    @Test
    fun `fullwidth currency symbols have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xFFE0)) // ¢
        assertEquals(2, WcWidth.charWidth(0xFFE1)) // £
        assertEquals(2, WcWidth.charWidth(0xFFE5)) // ¥
        assertEquals(2, WcWidth.charWidth(0xFFE6)) // ₩
    }

    // ── Hangul (width = 2) ─────────────────────────────────────────

    @Test
    fun `Hangul Jamo has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1100))
        assertEquals(2, WcWidth.charWidth(0x1159))
    }

    @Test
    fun `Hangul Jamo extended-A has width 2`() {
        assertEquals(2, WcWidth.charWidth(0xA960))
        assertEquals(2, WcWidth.charWidth(0xA97C))
    }

    @Test
    fun `Hangul Syllables have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xAC00)) // 가
        assertEquals(2, WcWidth.charWidth(0xD7A3)) // 힣
    }

    // ── Emoji (width = 2 where applicable) ──────────────────────────

    @Test
    fun `emoji misc symbols have width 2`() {
        // These are in the known WcWidth emoji ranges
        assertEquals(2, WcWidth.charWidth(0x231A)) // watch
        assertEquals(2, WcWidth.charWidth(0x231B)) // hourglass
        assertEquals(2, WcWidth.charWidth(0x23F0)) // alarm clock
        assertEquals(2, WcWidth.charWidth(0x23F3)) // hourglass done
    }

    @Test
    fun `sun symbol is narrow in WcWidth ranges`() {
        assertEquals(1, WcWidth.charWidth(0x2600)) // ☀ not in WcWidth wide ranges
    }

    @Test
    fun `weather emoji in WcWidth ranges are width 2`() {
        assertEquals(2, WcWidth.charWidth(0x2614)) // ☔
        assertEquals(2, WcWidth.charWidth(0x2615)) // ☕
    }

    @Test
    fun `zodiac emoji have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x2648)) // ♈
        assertEquals(2, WcWidth.charWidth(0x2653)) // ♓
    }

    @Test
    fun `emoji hearts and symbols have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x2763)) // ❣
        assertEquals(2, WcWidth.charWidth(0x2764)) // ❤
        assertEquals(2, WcWidth.charWidth(0x2795)) // ➕
        assertEquals(2, WcWidth.charWidth(0x2797)) // ➗
    }

    @Test
    fun `emoji arrows and geometric have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x27A1)) // ➡
        assertEquals(2, WcWidth.charWidth(0x27B0)) // ➰
        assertEquals(2, WcWidth.charWidth(0x27BF)) // ➿
        assertEquals(2, WcWidth.charWidth(0x2B50)) // ⭐
        assertEquals(2, WcWidth.charWidth(0x2B55)) // ⭕
    }

    @Test
    fun `emoji transport symbols have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1F680)) // 🚀
        assertEquals(2, WcWidth.charWidth(0x1F6C5)) // 🛅
    }

    @Test
    fun `emoji smileys and people have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1F600)) // 😀
        assertEquals(2, WcWidth.charWidth(0x1F44D)) // 👍
        assertEquals(2, WcWidth.charWidth(0x1F64F)) // 🙏
    }

    @Test
    fun `emoji food and drink have width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1F355)) // 🍕
        assertEquals(2, WcWidth.charWidth(0x1F37C)) // 🍼
    }

    @Test
    fun `waving flag emoji has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1F3F4)) // 🏴
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
        assertEquals(0, WcWidth.charWidth(0x0489))
    }

    @Test
    fun `hebrew combining marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0x0591))
        assertEquals(0, WcWidth.charWidth(0x05BD))
        assertEquals(0, WcWidth.charWidth(0x05BF))
    }

    @Test
    fun `arabic combining marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0x064B))
        assertEquals(0, WcWidth.charWidth(0x065F))
    }

    @Test
    fun `variation selectors have width 0`() {
        assertEquals(0, WcWidth.charWidth(0xFE00))
        assertEquals(0, WcWidth.charWidth(0xFE0F))
    }

    @Test
    fun `combining half marks have width 0`() {
        assertEquals(0, WcWidth.charWidth(0xFE20))
        assertEquals(0, WcWidth.charWidth(0xFE2F))
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

    @Test
    fun `BOM zero-width no-break space has width 0`() {
        assertEquals(0, WcWidth.charWidth(0xFEFF))
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
    fun `Yi syllables and Yi radicals have width 2`() {
        assertEquals(2, WcWidth.charWidth(0xA000))
        assertEquals(2, WcWidth.charWidth(0xA4CF))
    }

    @Test
    fun `Kana Supplement has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1B000))
        assertEquals(2, WcWidth.charWidth(0x1B0FF))
    }

    @Test
    fun `Kana Extended-A has width 2`() {
        assertEquals(2, WcWidth.charWidth(0x1B100))
        assertEquals(2, WcWidth.charWidth(0x1B12F))
    }

    // ── Edge Cases ──────────────────────────────────────────────────

    @Test
    fun `negative codePoint returns 0`() {
        assertEquals(0, WcWidth.charWidth(-1))
        assertEquals(0, WcWidth.charWidth(-100))
    }

    @Test
    fun `codePoint 0 returns 0`() {
        assertEquals(0, WcWidth.charWidth(0))
    }

    @Test
    fun `codePoint above Unicode max returns 0`() {
        assertEquals(0, WcWidth.charWidth(0x110000))
        assertEquals(0, WcWidth.charWidth(0x200000))
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

    // ── stringWidth ─────────────────────────────────────────────────

    @Test
    fun `stringWidth handles mixed content`() {
        // "A" (1) + " " (1) = 2
        assertEquals(2, WcWidth.stringWidth("A "))
        // "Hello" = 5
        assertEquals(5, WcWidth.stringWidth("Hello"))
        // CJK chars = 2 each
        assertEquals(4, WcWidth.stringWidth("汉字"))
        // Mixed: "A" + " " + CJK = 1 + 1 + 2 = 4
        assertEquals(4, WcWidth.stringWidth("A 字"))
    }

    // ── isZeroWidth / isWide ────────────────────────────────────────

    @Test
    fun `isZeroWidth returns true for combining chars`() {
        assertTrue(WcWidth.isZeroWidth(0x0300))
        assertTrue(WcWidth.isZeroWidth(0x200B))
    }

    @Test
    fun `isZeroWidth returns false for normal chars`() {
        assertFalse(WcWidth.isZeroWidth('A'.code))
        assertFalse(WcWidth.isZeroWidth(0x4E00))
    }

    @Test
    fun `isWide returns true for CJK`() {
        assertTrue(WcWidth.isWide(0x4E00))
        assertTrue(WcWidth.isWide(0xAC00))
    }

    @Test
    fun `isWide returns false for ASCII`() {
        assertFalse(WcWidth.isWide('A'.code))
        assertFalse(WcWidth.isWide('1'.code))
    }
}
