/*
 * WcWidth.kt — Unicode character display width calculation.
 *
 * Returns 0 (combining / zero-width), 1 (normal), or 2 (wide — CJK, emoji).
 * Based on the Unicode East Asian Width property and emoji wide-char rules.
 *
 * Inspired by Termux's WcWidth.java but re-implemented in Kotlin with
 * an updated range table (Unicode 15.0).
 */

package com.interndra.terminal

object WcWidth {

    /**
     * Return the display width of the given Unicode code point.
     *
     * @param codePoint Unicode code point (0 .. 0x10FFFF).
     * @return 0 (combining/zero-width), 1 (narrow), or 2 (wide).
     */
    fun width(codePoint: Int): Int {
        if (codePoint <= 0 || codePoint > 0x10FFFF) return 0

        // Fast path for ASCII
        if (codePoint <= 0x007F) {
            // C0 controls (0x00-0x1F) and DEL (0x7F) are 0-width
            return if (codePoint <= 0x1F || codePoint == 0x7F) 0 else 1
        }

        // Soft hyphen (0xAD) is 1
        if (codePoint == 0x00AD) return 1

        // Combining diacritical marks
        if (codePoint in 0x0300..0x036F) return 0
        if (codePoint in 0x0483..0x0489) return 0
        if (codePoint in 0x0591..0x05BD) return 0
        if (codePoint == 0x05BF) return 0
        if (codePoint in 0x05C1..0x05C2) return 0
        if (codePoint in 0x05C4..0x05C5) return 0
        if (codePoint == 0x05C7) return 0
        if (codePoint in 0x0610..0x061A) return 0
        if (codePoint in 0x064B..0x065F) return 0
        if (codePoint == 0x0670) return 0
        if (codePoint in 0x06D6..0x06DC) return 0
        if (codePoint in 0x06DF..0x06E4) return 0
        if (codePoint in 0x06E7..0x06E8) return 0
        if (codePoint in 0x06EA..0x06ED) return 0
        if (codePoint == 0x0711) return 0
        if (codePoint in 0x0730..0x074A) return 0
        if (codePoint in 0x07A6..0x07B0) return 0
        if (codePoint in 0x07EB..0x07F3) return 0
        if (codePoint in 0x07FD..0x07FD) return 0
        if (codePoint in 0x0816..0x0819) return 0
        if (codePoint in 0x081B..0x0823) return 0
        if (codePoint in 0x0825..0x0827) return 0
        if (codePoint in 0x0829..0x082D) return 0
        if (codePoint in 0x0859..0x085B) return 0
        if (codePoint in 0x0898..0x089F) return 0
        if (codePoint in 0x08CA..0x08E1) return 0
        if (codePoint in 0x08E3..0x0903) return 0
        if (codePoint in 0x093A..0x093C) return 0
        if (codePoint in 0x093E..0x094F) return 0
        if (codePoint in 0x0951..0x0957) return 0
        if (codePoint in 0x0962..0x0963) return 0
        if (codePoint in 0x0981..0x0983) return 0
        if (codePoint == 0x09BC) return 0
        if (codePoint in 0x09BE..0x09C4) return 0
        if (codePoint in 0x09C7..0x09C8) return 0
        if (codePoint in 0x09CB..0x09CD) return 0
        if (codePoint == 0x09D7) return 0
        if (codePoint in 0x09E2..0x09E3) return 0
        if (codePoint in 0x09FE..0x09FE) return 0
        if (codePoint in 0x0A01..0x0A03) return 0
        if (codePoint == 0x0A3C) return 0
        if (codePoint in 0x0A3E..0x0A42) return 0
        if (codePoint in 0x0A47..0x0A48) return 0
        if (codePoint in 0x0A4B..0x0A4D) return 0
        if (codePoint == 0x0A51) return 0
        if (codePoint in 0x0A70..0x0A71) return 0
        if (codePoint == 0x0A75) return 0
        if (codePoint in 0x0A81..0x0A83) return 0
        if (codePoint == 0x0ABC) return 0
        if (codePoint in 0x0ABE..0x0AC5) return 0
        if (codePoint in 0x0AC7..0x0AC9) return 0
        if (codePoint in 0x0ACB..0x0ACD) return 0
        if (codePoint in 0x0AE2..0x0AE3) return 0
        if (codePoint in 0x0AFA..0x0AFF) return 0
        if (codePoint in 0x0B01..0x0B03) return 0
        if (codePoint == 0x0B3C) return 0
        if (codePoint in 0x0B3E..0x0B44) return 0
        if (codePoint in 0x0B47..0x0B48) return 0
        if (codePoint in 0x0B4B..0x0B4D) return 0
        if (codePoint in 0x0B55..0x0B57) return 0
        if (codePoint in 0x0B62..0x0B63) return 0
        if (codePoint == 0x0B82) return 0
        if (codePoint in 0x0BBE..0x0BC2) return 0
        if (codePoint in 0x0BC6..0x0BC8) return 0
        if (codePoint in 0x0BCA..0x0BCD) return 0
        if (codePoint == 0x0BD7) return 0
        if (codePoint in 0x0C00..0x0C04) return 0
        if (codePoint == 0x0C3C) return 0
        if (codePoint in 0x0C3E..0x0C44) return 0
        if (codePoint in 0x0C46..0x0C48) return 0
        if (codePoint in 0x0C4A..0x0C4D) return 0
        if (codePoint in 0x0C55..0x0C56) return 0
        if (codePoint in 0x0C62..0x0C63) return 0
        if (codePoint in 0x0C81..0x0C83) return 0
        if (codePoint == 0x0CBC) return 0
        if (codePoint in 0x0CBE..0x0CC4) return 0
        if (codePoint in 0x0CC6..0x0CC8) return 0
        if (codePoint in 0x0CCA..0x0CCD) return 0
        if (codePoint in 0x0CD5..0x0CD6) return 0
        if (codePoint in 0x0CE2..0x0CE3) return 0
        if (codePoint in 0x0D00..0x0D03) return 0
        if (codePoint in 0x0D3B..0x0D3C) return 0
        if (codePoint in 0x0D3E..0x0D44) return 0
        if (codePoint in 0x0D46..0x0D48) return 0
        if (codePoint in 0x0D4A..0x0D4D) return 0
        if (codePoint == 0x0D57) return 0
        if (codePoint in 0x0D62..0x0D63) return 0
        if (codePoint in 0x0D81..0x0D83) return 0
        if (codePoint == 0x0DCA) return 0
        if (codePoint in 0x0DCF..0x0DD4) return 0
        if (codePoint == 0x0DD6) return 0
        if (codePoint in 0x0DD8..0x0DDF) return 0
        if (codePoint in 0x0DF2..0x0DF3) return 0
        if (codePoint == 0x0E31) return 0
        if (codePoint in 0x0E34..0x0E3A) return 0
        if (codePoint in 0x0E47..0x0E4E) return 0
        if (codePoint == 0x0EB1) return 0
        if (codePoint in 0x0EB4..0x0EBC) return 0
        if (codePoint in 0x0EC8..0x0ECE) return 0
        if (codePoint in 0x0F18..0x0F19) return 0
        if (codePoint == 0x0F35) return 0
        if (codePoint == 0x0F37) return 0
        if (codePoint == 0x0F39) return 0
        if (codePoint in 0x0F3E..0x0F3F) return 0
        if (codePoint in 0x0F71..0x0F84) return 0
        if (codePoint in 0x0F86..0x0F87) return 0
        if (codePoint in 0x0F8D..0x0F97) return 0
        if (codePoint in 0x0F99..0x0FBC) return 0
        if (codePoint == 0x0FC6) return 0
        if (codePoint in 0x102B..0x103E) return 0
        if (codePoint in 0x1056..0x1059) return 0
        if (codePoint in 0x105E..0x1060) return 0
        if (codePoint in 0x1062..0x1064) return 0
        if (codePoint in 0x1067..0x106D) return 0
        if (codePoint in 0x1071..0x1074) return 0
        if (codePoint in 0x1082..0x108D) return 0
        if (codePoint == 0x108F) return 0
        if (codePoint in 0x109A..0x109D) return 0
        if (codePoint in 0x1100..0x1159) return 2 // Hangul Jamo
        if (codePoint in 0x115F..0x115F) return 2
        if (codePoint in 0x1160..0x11FF) return 0 // Hangul Jamo combining
        if (codePoint in 0x135D..0x135F) return 0
        if (codePoint in 0x1712..0x1715) return 0
        if (codePoint in 0x1732..0x1734) return 0
        if (codePoint in 0x1752..0x1753) return 0
        if (codePoint in 0x1772..0x1773) return 0
        if (codePoint in 0x17B4..0x17D3) return 0
        if (codePoint in 0x17DD..0x17DD) return 0
        if (codePoint in 0x180B..0x180F) return 0
        if (codePoint in 0x1885..0x1886) return 0
        if (codePoint in 0x18A9..0x18A9) return 0
        if (codePoint in 0x1920..0x192B) return 0
        if (codePoint in 0x1930..0x193B) return 0
        if (codePoint in 0x1A17..0x1A1B) return 0
        if (codePoint in 0x1A55..0x1A5E) return 0
        if (codePoint in 0x1A60..0x1A7C) return 0
        if (codePoint in 0x1A7F..0x1A7F) return 0
        if (codePoint in 0x1AB0..0x1ACE) return 0
        if (codePoint in 0x1B00..0x1B04) return 0
        if (codePoint in 0x1B34..0x1B44) return 0
        if (codePoint in 0x1B6B..0x1B73) return 0
        if (codePoint in 0x1B80..0x1B82) return 0
        if (codePoint in 0x1BA1..0x1BAD) return 0
        if (codePoint in 0x1BE6..0x1BF3) return 0
        if (codePoint in 0x1C24..0x1C37) return 0
        if (codePoint in 0x1CD0..0x1CD2) return 0
        if (codePoint in 0x1CD4..0x1CE8) return 0
        if (codePoint in 0x1CED..0x1CED) return 0
        if (codePoint in 0x1CF4..0x1CF4) return 0
        if (codePoint in 0x1CF7..0x1CF9) return 0
        if (codePoint in 0x1DC0..0x1DFF) return 0
        if (codePoint in 0x200B..0x200F) return 0
        if (codePoint in 0x2028..0x202E) return 0
        if (codePoint in 0x2060..0x2064) return 0
        if (codePoint in 0x2066..0x206F) return 0
        if (codePoint == 0x20A8) return 1 // Rupee sign is narrow
        if (codePoint in 0x20D0..0x20F0) return 0
        if (codePoint in 0x231A..0x231B) return 2 // Watch, hourglass
        if (codePoint in 0x2329..0x232A) return 2 // Angle brackets
        if (codePoint in 0x23E9..0x23EC) return 2 // Double triangles
        if (codePoint == 0x23F0) return 2
        if (codePoint == 0x23F3) return 2
        if (codePoint in 0x25FD..0x25FE) return 2
        if (codePoint in 0x2614..0x2615) return 2
        if (codePoint in 0x2648..0x2653) return 2
        if (codePoint == 0x267F) return 2
        if (codePoint in 0x2693..0x2693) return 2
        if (codePoint == 0x26A1) return 2
        if (codePoint in 0x26AA..0x26AB) return 2
        if (codePoint in 0x26BD..0x26BE) return 2
        if (codePoint in 0x26C4..0x26C5) return 2
        if (codePoint == 0x26CE) return 2
        if (codePoint == 0x26D4) return 2
        if (codePoint == 0x26EA) return 2
        if (codePoint in 0x26F2..0x26F3) return 2
        if (codePoint == 0x26F5) return 2
        if (codePoint == 0x26FA) return 2
        if (codePoint == 0x26FD) return 2
        if (codePoint == 0x2702) return 2
        if (codePoint in 0x2705..0x2705) return 2
        if (codePoint in 0x2708..0x270D) return 2
        if (codePoint == 0x270F) return 2
        if (codePoint in 0x2712..0x2712) return 2
        if (codePoint in 0x2714..0x2714) return 2
        if (codePoint in 0x2716..0x2716) return 2
        if (codePoint in 0x271D..0x271D) return 2
        if (codePoint in 0x2721..0x2721) return 2
        if (codePoint == 0x2728) return 2
        if (codePoint in 0x2733..0x2734) return 2
        if (codePoint == 0x2744) return 2
        if (codePoint == 0x2747) return 2
        if (codePoint in 0x274C..0x274C) return 2
        if (codePoint == 0x274E) return 2
        if (codePoint in 0x2753..0x2755) return 2
        if (codePoint == 0x2757) return 2
        if (codePoint in 0x2763..0x2764) return 2
        if (codePoint in 0x2795..0x2797) return 2
        if (codePoint in 0x27A1..0x27A1) return 2
        if (codePoint in 0x27B0..0x27B0) return 2
        if (codePoint in 0x27BF..0x27BF) return 2
        if (codePoint in 0x2934..0x2935) return 2
        if (codePoint in 0x2B05..0x2B07) return 2
        if (codePoint in 0x2B1B..0x2B1C) return 2
        if (codePoint in 0x2B50..0x2B50) return 2
        if (codePoint == 0x2B55) return 2
        if (codePoint in 0x2CEF..0x2CF1) return 0
        if (codePoint in 0x2D7F..0x2D7F) return 0
        if (codePoint in 0x2DE0..0x2DFF) return 0
        if (codePoint in 0x3000..0x3000) return 2 // Full-width space
        if (codePoint in 0x302A..0x302F) return 0
        if (codePoint in 0x3099..0x309A) return 0
        if (codePoint in 0xA66F..0xA672) return 0
        if (codePoint in 0xA674..0xA67D) return 0
        if (codePoint in 0xA69E..0xA69F) return 0
        if (codePoint in 0xA6F0..0xA6F1) return 0
        if (codePoint in 0xA802..0xA802) return 0
        if (codePoint in 0xA806..0xA806) return 0
        if (codePoint in 0xA80B..0xA80B) return 0
        if (codePoint in 0xA823..0xA827) return 0
        if (codePoint in 0xA82C..0xA82C) return 0
        if (codePoint in 0xA880..0xA881) return 0
        if (codePoint in 0xA8B4..0xA8C5) return 0
        if (codePoint in 0xA8E0..0xA8F1) return 0
        if (codePoint in 0xA8FF..0xA8FF) return 0
        if (codePoint in 0xA926..0xA92D) return 0
        if (codePoint in 0xA947..0xA953) return 0
        if (codePoint in 0xA960..0xA97C) return 2 // Hangul Jamo extended-A
        if (codePoint in 0xA980..0xA983) return 0
        if (codePoint in 0xA9B3..0xA9C0) return 0
        if (codePoint in 0xA9E5..0xA9E5) return 0
        if (codePoint in 0xAA29..0xAA36) return 0
        if (codePoint in 0xAA43..0xAA43) return 0
        if (codePoint in 0xAA4C..0xAA4D) return 0
        if (codePoint in 0xAA7B..0xAA7D) return 0
        if (codePoint in 0xAAB0..0xAAB0) return 0
        if (codePoint in 0xAAB2..0xAAB4) return 0
        if (codePoint in 0xAAB7..0xAAB8) return 0
        if (codePoint in 0xAABE..0xAABF) return 0
        if (codePoint in 0xAAC1..0xAAC1) return 0
        if (codePoint in 0xAAEB..0xAAEF) return 0
        if (codePoint in 0xAAF5..0xAAF6) return 0
        if (codePoint in 0xABE3..0xABEA) return 0
        if (codePoint in 0xABEC..0xABED) return 0
        if (codePoint in 0xFB1E..0xFB1E) return 0
        if (codePoint in 0xFE00..0xFE0F) return 0 // Variation selectors
        if (codePoint in 0xFE20..0xFE2F) return 0
        if (codePoint in 0xFEFF..0xFEFF) return 0 // BOM/ZWNBSP

        // CJK Unified Ideographs
        if (codePoint in 0x3400..0x4DBF) return 2  // CJK Extension A
        if (codePoint in 0x4E00..0x9FFF) return 2  // CJK Unified
        if (codePoint in 0xA000..0xA4CF) return 2  // Yi
        if (codePoint in 0xAC00..0xD7A3) return 2  // Hangul Syllables

        // High Surrogates, Low Surrogates, Private Use
        if (codePoint in 0xD800..0xDFFF) return 1
        if (codePoint in 0xE000..0xF8FF) return 1 // Private Use

        // CJK Compatibility Ideographs
        if (codePoint in 0xF900..0xFAFF) return 2

        // Fullwidth forms
        if (codePoint in 0xFF01..0xFF60) return 2
        if (codePoint == 0xFFE0) return 2
        if (codePoint == 0xFFE1) return 2
        if (codePoint in 0xFFE3..0xFFE3) return 2
        if (codePoint in 0xFFE5..0xFFE6) return 2

        // Supplementary ranges
        if (codePoint in 0x1B000..0x1B0FF) return 2 // Kana Supplement
        if (codePoint in 0x1B100..0x1B12F) return 2 // Kana Extended-A
        if (codePoint in 0x1F004..0x1F004) return 2 // Mahjong
        if (codePoint in 0x1F0CF..0x1F0CF) return 2 // Playing card
        if (codePoint in 0x1F18E..0x1F18E) return 2
        if (codePoint in 0x1F191..0x1F19A) return 2
        if (codePoint in 0x1F200..0x1F202) return 2
        if (codePoint in 0x1F210..0x1F23B) return 2
        if (codePoint in 0x1F240..0x1F248) return 2
        if (codePoint in 0x1F250..0x1F251) return 2
        if (codePoint in 0x1F260..0x1F265) return 2
        if (codePoint in 0x1F300..0x1F320) return 2 // Misc symbols
        if (codePoint in 0x1F32D..0x1F335) return 2
        if (codePoint in 0x1F337..0x1F37C) return 2
        if (codePoint in 0x1F37E..0x1F393) return 2
        if (codePoint in 0x1F3A0..0x1F3CA) return 2
        if (codePoint in 0x1F3CF..0x1F3D3) return 2
        if (codePoint in 0x1F3E0..0x1F3F0) return 2
        if (codePoint in 0x1F3F4..0x1F3F4) return 2
        if (codePoint in 0x1F3F8..0x1F43E) return 2
        if (codePoint == 0x1F440) return 2
        if (codePoint in 0x1F442..0x1F4FC) return 2
        if (codePoint in 0x1F4FF..0x1F53D) return 2
        if (codePoint in 0x1F54B..0x1F54E) return 2
        if (codePoint in 0x1F550..0x1F567) return 2
        if (codePoint == 0x1F57A) return 2
        if (codePoint in 0x1F595..0x1F596) return 2
        if (codePoint in 0x1F5A4..0x1F5A4) return 2
        if (codePoint in 0x1F5FB..0x1F64F) return 2
        if (codePoint in 0x1F680..0x1F6C5) return 2
        if (codePoint == 0x1F6CC) return 2
        if (codePoint in 0x1F6D0..0x1F6D2) return 2
        if (codePoint in 0x1F6D5..0x1F6D7) return 2
        if (codePoint in 0x1F6DC..0x1F6DF) return 2
        if (codePoint in 0x1F6EB..0x1F6EC) return 2
        if (codePoint in 0x1F6F3..0x1F6FC) return 2
        if (codePoint in 0x1F7E0..0x1F7EB) return 2
        if (codePoint in 0x1F7F0..0x1F7F0) return 2
        if (codePoint in 0x1F90C..0x1F93A) return 2
        if (codePoint in 0x1F93C..0x1F945) return 2
        if (codePoint in 0x1F947..0x1F9FF) return 2
        if (codePoint in 0x1FA00..0x1FA6F) return 2
        if (codePoint in 0x1FA70..0x1FA7C) return 2
        if (codePoint in 0x1FA80..0x1FA88) return 2
        if (codePoint in 0x1FA90..0x1FABD) return 2
        if (codePoint in 0x1FABF..0x1FAC5) return 2
        if (codePoint in 0x1FACE..0x1FADB) return 2
        if (codePoint in 0x1FAE0..0x1FAE8) return 2
        if (codePoint in 0x1FAF0..0x1FAF8) return 2
        if (codePoint in 0x20000..0x2FFFD) return 2 // CJK Extension B, C, D, E, F, G
        if (codePoint in 0x30000..0x3FFFD) return 2 // CJK Extension H

        // Default: narrow
        return 1
    }

    /**
     * Get the display width of a Unicode code point.
     * Returns 0 for combining/control, 1 for regular, 2 for wide (CJK/emoji).
     */
    fun charWidth(codepoint: Int): Int = width(codepoint)

    /**
     * Convenience: get the width of a Kotlin Char.
     */
    fun charWidth(ch: Char): Int = width(ch.code)

    /**
     * Calculate the total display width of a string.
     */
    fun stringWidth(str: String): Int {
        var w = 0
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            w += width(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * Check if a code point is a zero-width character.
     */
    fun isZeroWidth(codePoint: Int): Boolean = width(codePoint) == 0

    /**
     * Check if a code point is a wide character.
     */
    fun isWide(codePoint: Int): Boolean = width(codePoint) == 2
}
