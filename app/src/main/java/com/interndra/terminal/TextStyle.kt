/*
 * TextStyle.kt — Int-bitfield encoding of terminal text attributes.
 *
 * Encodes foreground color, background color, bold, italic, underline,
 * strikethrough, blink, reverse, dim, hidden, and "conceal" flags into
 * a single 32-bit integer for fast comparison and minimal memory.
 *
 * Color storage:
 *   - Bits 0-7 →  8-bit foreground color index (0 = black … 255)
 *   - Bits 8-15 → 8-bit background color index
 *   - Bits 16-23 → bit flags (bold, italic, etc.)
 *   - Bit 24 → true-color foreground flag (foreground is a 24-bit RGB)
 *   - Bit 25 → true-color background flag
 *   - Bits 26-31 → unused (future)
 *
 * When the true-color flag is set for a plane, the 8-bit colour index
 * is ignored and the RGB is stored as a separate Int (the TextStyle
 * carries an optional fgRgb / bgRgb in convenience methods, but the
 * 32-bit encoding keeps the common case cache-friendly).
 */

package com.interndra.terminal

object TextStyle {

    // ── Bit masks ──────────────────────────────────────────────────────
    private const val MASK_COLOR      = 0x000000FF
    private const val MASK_FG         = 0x000000FF        // bits 0-7
    private const val MASK_BG         = 0x0000FF00        // bits 8-15
    private const val MASK_FLAGS      = 0x00FF0000        // bits 16-23
    private const val SHIFT_FG        = 0
    private const val SHIFT_BG        = 8
    private const val SHIFT_FLAGS     = 16

    // ── Flag bits (within the flags byte) ──────────────────────────────
    private const val FLAG_BOLD         = 1 shl 0  // 0x01
    private const val FLAG_DIM          = 1 shl 1  // 0x02
    private const val FLAG_ITALIC       = 1 shl 2  // 0x04
    private const val FLAG_UNDERLINE    = 1 shl 3  // 0x08
    private const val FLAG_BLINK        = 1 shl 4  // 0x10
    private const val FLAG_REVERSE      = 1 shl 5  // 0x20
    private const val FLAG_STRIKETHROUGH = 1 shl 6 // 0x40
    private const val FLAG_HIDDEN       = 1 shl 7  // 0x80

    // ── True-color flags (bits 24-25) ──────────────────────────────────
    private const val FLAG_TRUE_COLOR_FG = 1 shl 24
    private const val FLAG_TRUE_COLOR_BG = 1 shl 25

    // ── Colour indices for the 16 ANSI colours ─────────────────────────
    /** Standard ANSI colours (indices 0-7). */
    val ANSI_PALETTE_16 = intArrayOf(
        0xFF1E1E1E.toInt(), //  0 Black
        0xFFE06C75.toInt(), //  1 Red
        0xFF98C379.toInt(), //  2 Green
        0xFFE5C07B.toInt(), //  3 Yellow
        0xFF61AFEF.toInt(), //  4 Blue
        0xFFC678DD.toInt(), //  5 Magenta
        0xFF56B6C2.toInt(), //  6 Cyan
        0xFFABB2BF.toInt()  //  7 White (light grey)
    )
    /** Bright ANSI colours (indices 8-15). */
    val ANSI_PALETTE_BRIGHT = intArrayOf(
        0xFF5C6370.toInt(), //  8 Bright Black (grey)
        0xFFBE5046.toInt(), //  9 Bright Red
        0xFF98C379.toInt(), // 10 Bright Green
        0xFFD19A66.toInt(), // 11 Bright Yellow
        0xFF61AFEF.toInt(), // 12 Bright Blue
        0xFFC678DD.toInt(), // 13 Bright Magenta
        0xFF56B6C2.toInt(), // 14 Bright Cyan
        0xFFFFFFFF.toInt()  // 15 Bright White
    )
    private const val DEFAULT_FG = 7  // White
    private const val DEFAULT_BG = 0  // Black

    // ── Encoding helpers ───────────────────────────────────────────────

    /** Encode foreground colour index + flags into a single int. */
    fun encode(
        foreground: Int = DEFAULT_FG,
        background: Int = DEFAULT_BG,
        bold: Boolean = false,
        dim: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        blink: Boolean = false,
        reverse: Boolean = false,
        strikethrough: Boolean = false,
        hidden: Boolean = false
    ): Int {
        var bits = (foreground and MASK_COLOR) shl SHIFT_FG
        bits = bits or ((background and MASK_COLOR) shl SHIFT_BG)

        var flags = 0
        if (bold)         flags = flags or FLAG_BOLD
        if (dim)          flags = flags or FLAG_DIM
        if (italic)       flags = flags or FLAG_ITALIC
        if (underline)    flags = flags or FLAG_UNDERLINE
        if (blink)        flags = flags or FLAG_BLINK
        if (reverse)      flags = flags or FLAG_REVERSE
        if (strikethrough) flags = flags or FLAG_STRIKETHROUGH
        if (hidden)       flags = flags or FLAG_HIDDEN

        bits = bits or ((flags and 0xFF) shl SHIFT_FLAGS)
        return bits
    }

    /** Reset / default style. */
    val DEFAULT: Int get() = encode()

    // ── Decoding helpers ───────────────────────────────────────────────

    fun foreground(code: Int): Int = (code shr SHIFT_FG) and MASK_COLOR
    fun background(code: Int): Int = (code shr SHIFT_BG) and MASK_COLOR

    fun bold(code: Int): Boolean         = (code shr SHIFT_FLAGS and FLAG_BOLD) != 0
    fun dim(code: Int): Boolean          = (code shr SHIFT_FLAGS and FLAG_DIM) != 0
    fun italic(code: Int): Boolean       = (code shr SHIFT_FLAGS and FLAG_ITALIC) != 0
    fun underline(code: Int): Boolean    = (code shr SHIFT_FLAGS and FLAG_UNDERLINE) != 0
    fun blink(code: Int): Boolean        = (code shr SHIFT_FLAGS and FLAG_BLINK) != 0
    fun reverse(code: Int): Boolean      = (code shr SHIFT_FLAGS and FLAG_REVERSE) != 0
    fun strikethrough(code: Int): Boolean = (code shr SHIFT_FLAGS and FLAG_STRIKETHROUGH) != 0
    fun hidden(code: Int): Boolean       = (code shr SHIFT_FLAGS and FLAG_HIDDEN) != 0

    fun isTrueColorFg(code: Int): Boolean = (code and FLAG_TRUE_COLOR_FG) != 0
    fun isTrueColorBg(code: Int): Boolean = (code and FLAG_TRUE_COLOR_BG) != 0

    /** Convert an ANSI colour index (0-15) to an ARGB int. */
    fun ansiColor(index: Int): Int = when {
        index in 0..7   -> ANSI_PALETTE_16[index]
        index in 8..15  -> ANSI_PALETTE_BRIGHT[index - 8]
        index in 16..231 -> {
            // 6×6×6 colour cube
            val i = index - 16
            val r = (i / 36) * 255 / 5
            val g = ((i % 36) / 6) * 255 / 5
            val b = (i % 6) * 255 / 5
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        index in 232..255 -> {
            // Greyscale ramp (232 = black, 255 = white)
            val grey = (index - 232) * 255 / 23
            0xFF000000.toInt() or (grey shl 16) or (grey shl 8) or grey
        }
        else -> ANSI_PALETTE_16[7] // default white
    }

    /** Resolve the ARGB colour for a given style code (handles true-color flag). */
    fun foregroundArgb(code: Int, trueColorOverride: Int? = null): Int {
        if (isTrueColorFg(code) && trueColorOverride != null) return trueColorOverride or 0xFF000000.toInt()
        return ansiColor(foreground(code))
    }

    fun backgroundArgb(code: Int, trueColorOverride: Int? = null): Int {
        if (isTrueColorBg(code) && trueColorOverride != null) return trueColorOverride or 0xFF000000.toInt()
        return ansiColor(background(code))
    }

    /** Build a human-readable description (for debugging). */
    fun describe(code: Int): String = buildString {
        append("fg="); append(foreground(code))
        append(" bg="); append(background(code))
        if (bold(code)) append(" BOLD")
        if (dim(code)) append(" DIM")
        if (italic(code)) append(" ITALIC")
        if (underline(code)) append(" UL")
        if (blink(code)) append(" BLINK")
        if (reverse(code)) append(" REV")
        if (strikethrough(code)) append(" STRIKE")
        if (hidden(code)) append(" HIDDEN")
        if (isTrueColorFg(code)) append(" TC_FG")
        if (isTrueColorBg(code)) append(" TC_BG")
    }
}
