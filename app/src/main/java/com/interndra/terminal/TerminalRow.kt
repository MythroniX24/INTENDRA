/*
 * TerminalRow.kt — A single row of terminal screen data.
 *
 * Each cell is represented by a character and an Int-encoded style (see TextStyle).
 * This is the building block for the TerminalEmulator screen buffer.
 */

package com.interndra.terminal

import java.util.Arrays

/**
 * A single row in the terminal screen buffer.
 *
 * @param columns Number of columns (width) this row was created for.
 */
class TerminalRow(val columns: Int) {

    /** The characters for each column. */
    val chars = CharArray(columns) { ' ' }

    /** The style code for each column (see [TextStyle]). */
    val styles = IntArray(columns) { TextStyle.DEFAULT }

    /** Optional true-color foreground RGB override (null if not set). */
    val trueColorFg = IntArray(columns) { 0 }

    /** Optional true-color background RGB override. */
    val trueColorBg = IntArray(columns) { 0 }

    /** Whether this row has been modified since last check. */
    var isWrapped: Boolean = false
        private set

    // ── Accessors ──────────────────────────────────────────────────────

    fun getChar(col: Int): Char {
        if (col < 0 || col >= columns) return ' '
        return chars[col]
    }

    fun getStyle(col: Int): Int {
        if (col < 0 || col >= columns) return TextStyle.DEFAULT
        return styles[col]
    }

    fun setChar(col: Int, char: Char) {
        if (col in 0 until columns) chars[col] = char
    }

    fun setStyle(col: Int, style: Int) {
        if (col in 0 until columns) styles[col] = style
    }

    fun setCell(col: Int, char: Char, style: Int) {
        if (col in 0 until columns) {
            chars[col] = char
            styles[col] = style
        }
    }

    fun setTrueColorFg(col: Int, rgb: Int) {
        if (col in 0 until columns) trueColorFg[col] = rgb
    }

    fun setTrueColorBg(col: Int, rgb: Int) {
        if (col in 0 until columns) trueColorBg[col] = rgb
    }

    /** Mark this row as wrapped from the previous line. */
    fun markWrapped() { isWrapped = true }

    /** Clear this row to empty. */
    fun clear() {
        chars.fill(' ')
        Arrays.fill(styles, TextStyle.DEFAULT)
        Arrays.fill(trueColorFg, 0)
        Arrays.fill(trueColorBg, 0)
        isWrapped = false
    }

    /** Copy data from another row. */
    fun copyFrom(other: TerminalRow) {
        System.arraycopy(other.chars, 0, chars, 0, minOf(columns, other.columns))
        System.arraycopy(other.styles, 0, styles, 0, minOf(columns, other.columns))
        System.arraycopy(other.trueColorFg, 0, trueColorFg, 0, minOf(columns, other.columns))
        System.arraycopy(other.trueColorBg, 0, trueColorBg, 0, minOf(columns, other.columns))
        isWrapped = other.isWrapped
    }

    /** Get the plain text of this row. */
    val text: String get() = chars.concatToString()

    /** Clone this row. */
    fun cloneRow(): TerminalRow {
        val r = TerminalRow(columns)
        System.arraycopy(chars, 0, r.chars, 0, columns)
        System.arraycopy(styles, 0, r.styles, 0, columns)
        System.arraycopy(trueColorFg, 0, r.trueColorFg, 0, columns)
        System.arraycopy(trueColorBg, 0, r.trueColorBg, 0, columns)
        r.isWrapped = isWrapped
        return r
    }

    override fun toString(): String = chars.concatToString()
}
