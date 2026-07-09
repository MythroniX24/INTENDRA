package com.interndra.service

import android.graphics.Color

/**
 * TerminalBuffer — ANSI escape sequence parser and terminal state machine.
 *
 * ## What this does
 * Raw shell output often contains ANSI escape codes for colors (`\033[32m`),
 * cursor movement (`\033[2J`, `\033[H`), and text styling. Without a parser,
 * these codes appear as garbage in the chat UI.
 *
 * This buffer:
 * - Strips non-visible escape codes for display purposes
 * - Maps ANSI colors to Android Color ints for Compose rendering
 * - Tracks cursor position for proper screen output
 * - Handles clear-screen and line-erase sequences
 * - Produces structured [TerminalLine] objects for the UI
 *
 * ## ANSI codes handled
 * - SGR (Select Graphic Rendition): 0, 1, 2, 4, 30-37, 40-47, 90-97, 100-107
 * - Cursor movement: CUU, CUD, CUF, CUB, CUP, HVP
 * - Screen ops: ED (clear), EL (erase line)
 * - Mode: save/restore cursor
 *
 * ## Usage
 * ```kotlin
 * val buffer = TerminalBuffer()
 * buffer.processOutput("\u001b[32mHello\u001b[0m")
 * val lines: List<TerminalLine> = buffer.flush()
 * ```
 */
class TerminalBuffer {

    /** A single line of terminal output, with optional color spans. */
    data class TerminalLine(
        val text: String,
        val spans: List<ColorSpan> = emptyList()
    )

    data class ColorSpan(
        val start: Int,
        val end: Int,
        val fgColor: Int? = null,
        val bgColor: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val underline: Boolean = false
    )

    // ── Cursor state ────────────────────────────────────────────────────
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0

    // ── SGR state ───────────────────────────────────────────────────────
    private var currentFg: Int? = null
    private var currentBg: Int? = null
    private var currentBold = false
    private var currentDim = false
    private var currentUnderline = false

    // ── Screen buffer ───────────────────────────────────────────────────
    /** Max rows to buffer before discarding oldest. */
    private val maxRows = 500
    private val screenBuffer = mutableListOf<LineData>()

    /** In-progress line (text being accumulated). */
    private val currentLine = StringBuilder()

    /** Spans for the current line being built. */
    private val currentSpans = mutableListOf<ColorSpan>()

    /** Buffer for partial escape sequences. */
    private val escapeBuffer = StringBuilder()
    private var inEscape = false
    private var escapeParams = mutableListOf<Int>()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Process a raw string containing ANSI escape codes.
     * Builds internal buffer state. Call [flush] to get structured lines.
     *
     * Thread-safe — can be called from multiple coroutines.
     */
    @Synchronized
    fun processOutput(raw: String) {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]

            if (inEscape) {
                escapeBuffer.append(ch)
                when {
                    // ESC [ ... final byte
                    ch in 'A'..'Z' || ch in 'a'..'z' || ch == '@' || ch == '`' || ch == '{' || ch == '}' || ch == '~' -> {
                        handleEscapeSequence(escapeBuffer.toString())
                        escapeBuffer.clear()
                        escapeParams.clear()
                        inEscape = false
                    }
                    // semicolons separate params
                    ch == ';' -> {
                        escapeParams.add(escapeBuffer.drop(1).trimEnd(';').split(';').last().toIntOrNull() ?: 0)
                    }
                }
            } else if (ch == '\u001b') {
                inEscape = true
                escapeBuffer.append(ch)
            } else if (ch == '\r') {
                // Carriage return: move to column 0
                cursorCol = 0
                // If followed by \n, the \n handles newline
            } else if (ch == '\n') {
                commitLine()
            } else if (ch == '\b') {
                if (currentLine.isNotEmpty()) {
                    currentLine.deleteCharAt(currentLine.length - 1)
                    cursorCol = (cursorCol - 1).coerceAtLeast(0)
                }
            } else if (ch == '\t') {
                val spaces = 8 - (cursorCol % 8)
                appendText(" ".repeat(spaces))
            } else if (ch.isISOControl()) {
                // Skip other control chars (BEL, FF, etc.)
            } else {
                appendText(ch.toString())
            }

            i++
        }
    }

    /**
     * Flush all buffered lines and return as [TerminalLine] objects.
     * Clears the internal buffer.
     *
     * Thread-safe.
     */
    @Synchronized
    fun flush(): List<TerminalLine> {
        if (currentLine.isNotEmpty()) {
            commitLine()
        }
        val result = screenBuffer.map { lineData ->
            TerminalLine(text = lineData.text, spans = mergeSpans(lineData.spans))
        }.toList()
        screenBuffer.clear()
        return result
    }

    /**
     * Flush and return plain text (all ANSI stripped). Thread-safe.
     */
    @Synchronized
    fun flushPlainText(): String {
        return flush().joinToString("\n") { it.text }
    }

    /** Reset all state. Thread-safe. */
    @Synchronized
    fun reset() {
        cursorRow = 0; cursorCol = 0
        currentLine.clear(); currentSpans.clear()
        screenBuffer.clear()
        escapeBuffer.clear(); escapeParams.clear()
        inEscape = false
        currentFg = null; currentBg = null
        currentBold = false; currentDim = false; currentUnderline = false
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun appendText(text: String) {
        val start = currentLine.length
        currentLine.append(text)
        cursorCol += text.length

        // Record color span if any styling is active
        if (currentFg != null || currentBg != null || currentBold || currentDim || currentUnderline) {
            currentSpans.add(ColorSpan(
                start = start, end = currentLine.length,
                fgColor = currentFg, bgColor = currentBg,
                bold = currentBold, dim = currentDim, underline = currentUnderline
            ))
        }
    }

    private fun commitLine() {
        val text = currentLine.toString()
        // Trim trailing spaces but keep content
        screenBuffer.add(LineData(text, currentSpans.toList()))
        // Limit buffer size
        while (screenBuffer.size > maxRows) {
            screenBuffer.removeAt(0)
        }
        currentLine.clear(); currentSpans.clear()
        cursorRow++; cursorCol = 0
    }

    /** Handle a complete ANSI escape sequence. */
    private fun handleEscapeSequence(seq: String) {
        // Parse params if we haven't already
        if (escapeParams.isEmpty() && seq.length > 2) {
            val paramsStr = seq.drop(2).dropLast(1)
            if (paramsStr.isNotEmpty()) {
                escapeParams.addAll(paramsStr.split(';').map { it.toIntOrNull() ?: 0 })
            }
        }
        val final = seq.last()
        val p = escapeParams

        when (final) {
            'm' -> handleSGR(p)
            'A' -> moveCursor(0, -(p.firstOrNull() ?: 1))  // CUU
            'B' -> moveCursor(0, p.firstOrNull() ?: 1)     // CUD
            'C' -> moveCursor(p.firstOrNull() ?: 1, 0)     // CUF
            'D' -> moveCursor(-(p.firstOrNull() ?: 1), 0)  // CUB
            'H', 'f' -> {  // CUP / HVP
                val row = (p.getOrNull(0) ?: 1) - 1
                val col = (p.getOrNull(1) ?: 1) - 1
                cursorRow = row.coerceAtLeast(0)
                cursorCol = col.coerceAtLeast(0)
            }
            'J' -> {  // ED — Erase Display
                when (p.firstOrNull() ?: 0) {
                    0 -> eraseFromCursor()    // Clear from cursor to end
                    1 -> eraseToCursor()      // Clear from start to cursor
                    2, 3 -> {                 // Clear entire screen
                        screenBuffer.clear()
                        currentLine.clear(); currentSpans.clear()
                        cursorRow = 0; cursorCol = 0
                    }
                }
            }
            'K' -> {  // EL — Erase in Line
                when (p.firstOrNull() ?: 0) {
                    0 -> { // Clear from cursor to end of line
                        if (cursorCol < currentLine.length) {
                            currentLine.delete(cursorCol, currentLine.length)
                        }
                    }
                    1 -> { // Clear from start to cursor
                        if (cursorCol > 0) {
                            currentLine.delete(0, cursorCol)
                        }
                    }
                    2 -> { // Clear entire line
                        currentLine.clear()
                        currentSpans.clear()
                    }
                }
            }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }  // Save cursor
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }  // Restore cursor
        }
    }

    private fun handleSGR(params: List<Int>) {
        val p = if (params.isEmpty()) listOf(0) else params
        var i = 0
        while (i < p.size) {
            val code = p[i]
            when (code) {
                0 -> { currentFg = null; currentBg = null; currentBold = false; currentDim = false; currentUnderline = false }
                1 -> currentBold = true
                2 -> { currentDim = true; currentBold = false }
                4 -> currentUnderline = true
                22 -> { currentBold = false; currentDim = false }
                24 -> currentUnderline = false
                30 -> currentFg = Color.BLACK
                31 -> currentFg = Color.RED
                32 -> currentFg = Color.rgb(0, 170, 0)     // Green
                33 -> currentFg = Color.rgb(170, 170, 0)   // Yellow
                34 -> currentFg = Color.BLUE
                35 -> currentFg = Color.MAGENTA
                36 -> currentFg = Color.CYAN
                37 -> currentFg = Color.WHITE
                38 -> {  // Extended foreground (skip for now)
                    if (i + 2 < p.size && p[i + 1] == 5) { i += 2 }  // 256-color
                    else if (i + 4 < p.size && p[i + 1] == 2) { i += 4 }  // True color
                }
                39 -> currentFg = null  // Default fg
                40 -> currentBg = Color.BLACK
                41 -> currentBg = Color.RED
                42 -> currentBg = Color.rgb(0, 170, 0)
                43 -> currentBg = Color.rgb(170, 170, 0)
                44 -> currentBg = Color.BLUE
                45 -> currentBg = Color.MAGENTA
                46 -> currentBg = Color.CYAN
                47 -> currentBg = Color.WHITE
                48 -> {  // Extended background (skip for now)
                    if (i + 2 < p.size && p[i + 1] == 5) { i += 2 }
                    else if (i + 4 < p.size && p[i + 1] == 2) { i += 4 }
                }
                49 -> currentBg = null  // Default bg
                90 -> currentFg = Color.DKGRAY     // Bright black
                91 -> currentFg = Color.rgb(255, 100, 100)  // Bright red
                92 -> currentFg = Color.rgb(100, 255, 100)  // Bright green
                93 -> currentFg = Color.YELLOW              // Bright yellow
                94 -> currentFg = Color.rgb(100, 100, 255)  // Bright blue
                95 -> currentFg = Color.rgb(255, 100, 255)  // Bright magenta
                96 -> currentFg = Color.rgb(100, 255, 255)  // Bright cyan
                97 -> currentFg = Color.LTGRAY             // Bright white
                100 -> currentBg = Color.DKGRAY
                101 -> currentBg = Color.rgb(255, 100, 100)
                102 -> currentBg = Color.rgb(100, 255, 100)
                103 -> currentBg = Color.YELLOW
                104 -> currentBg = Color.rgb(100, 100, 255)
                105 -> currentBg = Color.rgb(255, 100, 255)
                106 -> currentBg = Color.rgb(100, 255, 255)
                107 -> currentBg = Color.LTGRAY
            }
            i++
        }
    }

    private fun moveCursor(dc: Int, dr: Int) {
        cursorCol = (cursorCol + dc).coerceAtLeast(0)
        cursorRow = (cursorRow + dr).coerceAtLeast(0)
    }

    private fun eraseFromCursor() {
        if (cursorCol < currentLine.length) {
            currentLine.delete(cursorCol, currentLine.length)
        }
        // Remove subsequent lines from screenBuffer
        val removeFrom = cursorRow + 1
        while (screenBuffer.size > removeFrom) {
            screenBuffer.removeAt(screenBuffer.size - 1)
        }
    }

    private fun eraseToCursor() {
        currentLine.delete(0, cursorCol)
        // Remove previous lines
        while (cursorRow > 0 && screenBuffer.size >= cursorRow) {
            screenBuffer.removeAt(0)
        }
    }

    /** Merge overlapping color spans into a clean list. */
    private fun mergeSpans(spans: List<ColorSpan>): List<ColorSpan> {
        if (spans.isEmpty()) return emptyList()
        val merged = mutableListOf<ColorSpan>()
        var current = spans.first()
        for (next in spans.drop(1)) {
            if (next.start <= current.end &&
                next.fgColor == current.fgColor && next.bgColor == current.bgColor &&
                next.bold == current.bold && next.dim == current.dim && next.underline == current.underline) {
                current = current.copy(end = maxOf(current.end, next.end))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /** Internal mutable line representation. */
    private data class LineData(val text: String, val spans: List<ColorSpan>)
}
