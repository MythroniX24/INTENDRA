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
 * - SGR (Select Graphic Rendition): 0, 1, 2, 4, 22, 24, 30-37, 40-47, 90-97, 100-107
 * - Cursor movement: CUU, CUD, CUF, CUB, CUP, HVP
 * - Screen ops: ED (clear), EL (erase line)
 * - Mode: save/restore cursor, DSR/DECSET (silently consumed)
 * - Character set selection (silently consumed)
 * - OSC (window title, clipboard) — silently consumed with BEL / ST terminators
 * - SOS, PM, APC — silently consumed
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

    // ── Constants ───────────────────────────────────────────────────────
    /** Max rows to buffer before discarding oldest. */
    private val maxRows = 500

    /** Max escape sequence length before we abort and discard. */
    private val maxEscapeLength = 128

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
    private val screenBuffer = mutableListOf<LineData>()

    /** In-progress line (text being accumulated). */
    private val currentLine = StringBuilder()

    /** Spans for the current line being built. */
    private val currentSpans = mutableListOf<ColorSpan>()

    /** Buffer for partial escape sequences. */
    private val escapeBuffer = StringBuilder()
    private var inEscape = false

    /** True when inside a non-CSI escape (OSC `]`, SOS `X`, PM `^`, APC `_`). */
    private var inNonCsiEscape = false

    /** Cached parsed params to avoid re-parsing when sequence arrives across multiple calls. */
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
                processEscapeChar(ch)
            } else if (ch == '\u001b') {
                // Start of an escape sequence
                inEscape = true
                inNonCsiEscape = false
                escapeBuffer.clear()
                escapeParams.clear()
                escapeBuffer.append(ch)
            } else if (ch == '\r') {
                // Carriage return: move to column 0
                cursorCol = 0
            } else if (ch == '\n') {
                commitLine()
            } else if (ch == '\b') {
                if (currentLine.isNotEmpty()) {
                    currentLine.deleteCharAt(currentLine.length - 1)
                    // Also trim any color spans that extend past the new length
                    trimSpansToLength(currentLine.length)
                    cursorCol = (cursorCol - 1).coerceAtLeast(0)
                }
            } else if (ch == '\t') {
                val spaces = 8 - (cursorCol % 8)
                appendText(" ".repeat(spaces))
            } else if (ch.isISOControl()) {
                // Skip other control chars (BEL - handled in escape mode, FF, etc.)
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
        inEscape = false; inNonCsiEscape = false
        currentFg = null; currentBg = null
        currentBold = false; currentDim = false; currentUnderline = false
    }

    // ── Escape state machine ────────────────────────────────────────────

    /**
     * Process a character while inside an escape sequence.
     *
     * The escape state machine handles:
     * - CSI sequences: `\033[ params... final`  (final in A-Z, a-z, @, `, {, }, ~)
     * - OSC sequences: `\033] ... \007` or `\033] ... \033\` (String Terminator)
     * - Character set: `\033(` or `\033)`   — silently consumed
     * - SOS `\033X`, PM `\033^`, APC `\033_` — consumed until ST (`\033\`)
     * - ST terminator `\033\` — ends any non-CSI escape
     * - BEL `\u0007` — ends OSC sequences
     */
    private fun processEscapeChar(ch: Char) {
        // ── Escape buffer overflow protection ────────────────────────
        if (escapeBuffer.length >= maxEscapeLength) {
            // Sequence too long — probably garbage. Abort.
            escapeBuffer.clear()
            escapeParams.clear()
            inEscape = false
            inNonCsiEscape = false
            // Treat remaining chars as literal (fall through to non-escape logic)
            // But we can't easily replay here, so just discard.
            return
        }

        escapeBuffer.append(ch)

        // ── Detect escape type on the second character ───────────────
        if (escapeBuffer.length == 2) {
            when (ch) {
                '[' -> inNonCsiEscape = false  // CSI sequence
                ']' -> inNonCsiEscape = true   // OSC sequence
                '(' -> { /* Character set select - consume but ignore */ }
                ')' -> { /* Character set select - consume but ignore */ }
                'X' -> inNonCsiEscape = true   // SOS (Start of String)
                '^' -> inNonCsiEscape = true   // PM (Privacy Message)
                '_' -> inNonCsiEscape = true   // APC (Application Program Command)
                'P' -> inNonCsiEscape = true   // DCS (Device Control String)
                'k' -> inNonCsiEscape = true   // Linux private escape
                else -> {
                    // Single two-char escape like \033=, \033>, \0337, \0338, \033c
                    if (ch in '\u0020'..'\u002f' || ch in '\u0030'..'\u007e') {
                        // ANSI control sequences: ESC + single intermediate/final char
                        finalizeEscape()
                    }
                }
            }
            // For `(` and `)`, sequence is done after 2 chars
            if (ch == '(' || ch == ')') {
                finalizeEscape()
            }
            return
        }

        // ── Handle based on escape type ──────────────────────────────
        if (inNonCsiEscape) {
            // Non-CSI escape (OSC, SOS, PM, APC, DCS)
            when {
                ch == '\u0007' -> {
                    // BEL terminates: \033]...\007
                    finalizeEscape()
                }
                ch == '\u001b' && escapeBuffer.length >= 3 -> {
                    // Could be ST (\033\) or nested escape — wait for next char
                }
                ch == '\\' && escapeBuffer.length >= 3 && escapeBuffer[escapeBuffer.length - 2] == '\u001b' -> {
                    // ST (String Terminator): \033\
                    finalizeEscape()
                }
            }
        } else {
            // CSI sequence: ends with a final byte
            val isFinalByte = ch in 'A'..'Z' || ch in 'a'..'z' ||
                ch == '@' || ch == '`' || ch == '{' || ch == '}' || ch == '~'

            if (isFinalByte) {
                handleEscapeSequence(escapeBuffer.toString())
                finalizeEscape()
            } else if (ch == '\u0007') {
                // Some programs send BEL as CSI terminator (shouldn't but handle it)
                finalizeEscape()
            }
        }
    }

    /** Clean up after a complete escape sequence. */
    private fun finalizeEscape() {
        escapeBuffer.clear()
        escapeParams.clear()
        inEscape = false
        inNonCsiEscape = false
    }

    /** Handle a complete CSI escape sequence. */
    private fun handleEscapeSequence(seq: String) {
        val final = seq.last()

        // Parse params: strip CSI introducer (\u001b[) and final byte
        if (escapeParams.isEmpty() && seq.length > 2 && seq[1] == '[') {
            var paramsStr = seq.drop(2).dropLast(1)
            // Strip leading '?' from DECSET/DECRST params (\u001b[?25l)
            if (paramsStr.startsWith('?')) {
                paramsStr = paramsStr.drop(1)
            }
            if (paramsStr.isNotEmpty()) {
                escapeParams.addAll(paramsStr.split(';').map { it.toIntOrNull() ?: 0 })
            }
        }

        val p = escapeParams

        when (final) {
            'm' -> handleSGR(p)
            'A' -> moveCursor(0, -(p.firstOrNull() ?: 1))  // CUU
            'B' -> moveCursor(0, p.firstOrNull() ?: 1)     // CUD
            'C' -> moveCursor(p.firstOrNull() ?: 1, 0)     // CUF
            'D' -> moveCursor(-(p.firstOrNull() ?: 1), 0)  // CUB
            'E' -> { // CNL — Cursor Next Line
                cursorCol = 0
                cursorRow += (p.firstOrNull() ?: 1)
            }
            'F' -> { // CPL — Cursor Previous Line
                cursorCol = 0
                cursorRow = (cursorRow - (p.firstOrNull() ?: 1)).coerceAtLeast(0)
            }
            'G' -> { // CHA — Cursor Horizontal Absolute
                cursorCol = ((p.firstOrNull() ?: 1) - 1).coerceAtLeast(0)
            }
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
                            trimSpansToLength(cursorCol)
                        }
                    }
                    1 -> { // Clear from start to cursor
                        if (cursorCol > 0 && cursorCol <= currentLine.length) {
                            currentLine.delete(0, cursorCol)
                            // Shift and trim spans
                            shiftSpans(-cursorCol)
                        }
                    }
                    2 -> { // Clear entire line
                        currentLine.clear()
                        currentSpans.clear()
                    }
                }
            }
            'L' -> { // IL — Insert Line
                // Insert blank lines at cursor row (simplified: just add newline)
                val n = p.firstOrNull() ?: 1
                repeat(n) {
                    screenBuffer.add(LineData("", emptyList()))
                }
            }
            'M' -> { // DL — Delete Line
                val n = p.firstOrNull() ?: 1
                repeat(n.coerceAtMost(screenBuffer.size)) {
                    screenBuffer.removeAt(screenBuffer.size - 1)
                }
            }
            'P' -> { // DCH — Delete Character
                val n = p.firstOrNull() ?: 1
                if (cursorCol < currentLine.length) {
                    val deleteEnd = (cursorCol + n).coerceAtMost(currentLine.length)
                    currentLine.delete(cursorCol, deleteEnd)
                    trimSpansAfterDelete(cursorCol, deleteEnd - cursorCol)
                }
            }
            'X' -> { // ECH — Erase Character
                val n = p.firstOrNull() ?: 1
                if (cursorCol < currentLine.length) {
                    val eraseEnd = (cursorCol + n).coerceAtMost(currentLine.length)
                    // Replace with spaces (or just delete)
                    currentLine.replace(cursorCol, eraseEnd, " ".repeat(eraseEnd - cursorCol))
                }
            }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }  // Save cursor
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }  // Restore cursor
            'h', 'l' -> { /* DECSET/DECRST — silently ignored */ }
            // CSI final bytes that we intentionally ignore
            'c' -> { /* Device Attributes request */ }
            'n' -> { /* Device Status Report */ }
            't' -> { /* Window manipulation */ }
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
                3 -> { /* Italic — not rendered but tracked */ }
                4 -> currentUnderline = true
                5, 6 -> { /* Blink — ignored */ }
                7 -> { /* Inverse/reverse — ignored */ }
                8 -> { /* Conceal — ignored */ }
                21 -> { /* Double underline — treat as underline */ currentUnderline = true }
                22 -> { currentBold = false; currentDim = false }
                23 -> { /* Italic off */ }
                24 -> currentUnderline = false
                25, 26 -> { /* Blink off */ }
                27 -> { /* Inverse off */ }
                28 -> { /* Conceal off */ }
                29 -> { /* Crossed-out off */ }
                30 -> currentFg = Color.BLACK
                31 -> currentFg = Color.RED
                32 -> currentFg = Color.rgb(0, 170, 0)     // Green
                33 -> currentFg = Color.rgb(170, 170, 0)   // Yellow
                34 -> currentFg = Color.BLUE
                35 -> currentFg = Color.MAGENTA
                36 -> currentFg = Color.CYAN
                37 -> currentFg = Color.WHITE
                38 -> {  // Extended foreground
                    if (i + 2 < p.size && p[i + 1] == 5) {
                        // 256-color: \u001b[38;5;N
                        val color256 = p[i + 2]
                        currentFg = color256ToRgb(color256)
                        i += 2
                    } else if (i + 4 < p.size && p[i + 1] == 2) {
                        // True color: \u001b[38;2;R;G;B
                        currentFg = Color.rgb(
                            p[i + 2].coerceIn(0, 255),
                            p[i + 3].coerceIn(0, 255),
                            p[i + 4].coerceIn(0, 255)
                        )
                        i += 4
                    }
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
                48 -> {  // Extended background
                    if (i + 2 < p.size && p[i + 1] == 5) {
                        val color256 = p[i + 2]
                        currentBg = color256ToRgb(color256)
                        i += 2
                    } else if (i + 4 < p.size && p[i + 1] == 2) {
                        currentBg = Color.rgb(
                            p[i + 2].coerceIn(0, 255),
                            p[i + 3].coerceIn(0, 255),
                            p[i + 4].coerceIn(0, 255)
                        )
                        i += 4
                    }
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

    /** Map xterm 256-color index to an Android Color int. */
    private fun color256ToRgb(index: Int): Int {
        return when {
            index < 0 -> Color.BLACK
            index < 16 -> ansiToRgb(index) // 16 standard ANSI colors
            index < 232 -> {
                // 216-color cube (6×6×6)
                val ci = index - 16
                val r = (ci / 36) % 6; val g = (ci / 6) % 6; val b = ci % 6
                fun scale(v: Int) = if (v == 0) 0 else v * 40 + 55
                Color.rgb(scale(r), scale(g), scale(b))
            }
            index < 256 -> {
                // Grayscale ramp (232-255)
                val gray = (index - 232) * 10 + 8
                Color.rgb(gray, gray, gray)
            }
            else -> Color.WHITE
        }
    }

    /** Map console index 0-15 to RGB. */
    private fun ansiToRgb(index: Int): Int {
        return when (index) {
            0 -> Color.BLACK
            1 -> Color.RED
            2 -> Color.rgb(0, 170, 0)
            3 -> Color.rgb(170, 170, 0)
            4 -> Color.BLUE
            5 -> Color.MAGENTA
            6 -> Color.CYAN
            7 -> Color.LTGRAY
            8 -> Color.DKGRAY
            9 -> Color.rgb(255, 100, 100)
            10 -> Color.rgb(100, 255, 100)
            11 -> Color.YELLOW
            12 -> Color.rgb(100, 100, 255)
            13 -> Color.rgb(255, 100, 255)
            14 -> Color.rgb(100, 255, 255)
            15 -> Color.WHITE
            else -> Color.WHITE
        }
    }

    private fun moveCursor(dc: Int, dr: Int) {
        cursorCol = (cursorCol + dc).coerceAtLeast(0)
        cursorRow = (cursorRow + dr).coerceAtLeast(0)
    }

    private fun eraseFromCursor() {
        if (cursorCol < currentLine.length) {
            currentLine.delete(cursorCol, currentLine.length)
            trimSpansToLength(cursorCol)
        }
        // Remove subsequent lines from screenBuffer
        while (screenBuffer.size > cursorRow + 1) {
            screenBuffer.removeAt(screenBuffer.size - 1)
        }
    }

    private fun eraseToCursor() {
        if (cursorCol > 0 && cursorCol <= currentLine.length) {
            currentLine.delete(0, cursorCol)
            shiftSpans(-cursorCol)
        }
        // Remove previous lines
        while (cursorRow > 0 && screenBuffer.size >= cursorRow) {
            screenBuffer.removeAt(0)
        }
    }

    // ── Text and span management ─────────────────────────────────────────

    private fun appendText(text: String) {
        val start = cursorCol

        if (cursorCol <= currentLine.length) {
            // Overwrite at cursor position (e.g., after \r)
            val before = currentLine.substring(0, cursorCol)
            val afterLen = (cursorCol + text.length).coerceAtMost(currentLine.length)
            val after = currentLine.substring(afterLen)

            // Remove spans that overlap with the overwritten range
            val overwriteEnd = cursorCol + text.length
            trimSpansInRange(cursorCol, overwriteEnd)

            currentLine.clear()
            currentLine.append(before)
            currentLine.append(text)
            currentLine.append(after)
        } else {
            // Append at end of line (normal flow)
            currentLine.append(text)
        }
        cursorCol += text.length

        // Record color span if any styling is active
        if (currentFg != null || currentBg != null || currentBold || currentDim || currentUnderline) {
            currentSpans.add(ColorSpan(
                start = start, end = cursorCol,
                fgColor = currentFg, bgColor = currentBg,
                bold = currentBold, dim = currentDim, underline = currentUnderline
            ))
        }
    }

    private fun commitLine() {
        val text = currentLine.toString()
        screenBuffer.add(LineData(text, currentSpans.toList()))
        // Limit buffer size
        while (screenBuffer.size > maxRows) {
            screenBuffer.removeAt(0)
        }
        currentLine.clear(); currentSpans.clear()
        cursorRow++; cursorCol = 0
    }

    /**
     * Trim any spans that extend beyond `newLength`.
     * Called after backspace or EL erase.
     */
    private fun trimSpansToLength(newLength: Int) {
        val iterator = currentSpans.iterator()
        while (iterator.hasNext()) {
            val span = iterator.next()
            if (span.start >= newLength) {
                iterator.remove()
            } else if (span.end > newLength) {
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(end = newLength)
            }
        }
    }

    /**
     * Remove spans that overlap with a range being overwritten.
     * Called after carriage-return overwrite in appendText().
     */
    private fun trimSpansInRange(rangeStart: Int, rangeEnd: Int) {
        val iterator = currentSpans.iterator()
        while (iterator.hasNext()) {
            val span = iterator.next()
            if (span.start >= rangeEnd) {
                // Span starts after overwrite — shift it left
                val shrink = rangeEnd - rangeStart
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(
                    start = (span.start - shrink).coerceAtLeast(rangeStart),
                    end = (span.end - shrink).coerceAtLeast(rangeStart)
                )
            } else if (span.end > rangeStart && span.start < rangeEnd) {
                // Span overlaps with overwritten area — trim or remove
                if (span.start >= rangeStart) {
                    // Span starts within overwritten area — remove entirely
                    iterator.remove()
                } else {
                    // Span starts before overwrite, ends within it — truncate
                    val idx = currentSpans.indexOf(span)
                    currentSpans[idx] = span.copy(end = rangeStart)
                }
            }
        }
    }

    /**
     * Shift all spans by `delta` positions (for EL 1K erase).
     * Negative delta shifts left.
     */
    private fun shiftSpans(delta: Int) {
        val iterator = currentSpans.iterator()
        while (iterator.hasNext()) {
            val span = iterator.next()
            val newStart = span.start + delta
            val newEnd = span.end + delta
            if (newEnd <= 0) {
                iterator.remove()
            } else {
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(
                    start = newStart.coerceAtLeast(0),
                    end = newEnd.coerceAtLeast(1)
                )
            }
        }
    }

    /**
     * Trim spans after deleting `count` characters starting at `start`.
     * Called for DCH (`\u001b[P`).
     */
    private fun trimSpansAfterDelete(start: Int, count: Int) {
        val iterator = currentSpans.iterator()
        while (iterator.hasNext()) {
            val span = iterator.next()
            if (span.start >= start + count) {
                // Span is entirely after deleted region — shift left
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(
                    start = span.start - count,
                    end = span.end - count
                )
            } else if (span.start >= start && span.start < start + count) {
                // Span starts within deleted region
                if (span.end <= start + count) {
                    // Entire span within deleted region — remove
                    iterator.remove()
                } else {
                    // Span extends past deleted region — truncate start
                    val idx = currentSpans.indexOf(span)
                    currentSpans[idx] = span.copy(
                        start = start,
                        end = span.end - count
                    )
                }
            } else if (span.end > start && span.end <= start + count) {
                // Span starts before but ends within deleted region
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(end = start)
            } else if (span.start < start && span.end > start + count) {
                // Span spans across the entire deleted region
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(end = span.end - count)
            }
        }
    }

    /** Merge overlapping or adjacent color spans with identical attributes. */
    private fun mergeSpans(spans: List<ColorSpan>): List<ColorSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.start }
        val merged = mutableListOf<ColorSpan>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            // Merge if overlapping/adjacent AND same attributes
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
