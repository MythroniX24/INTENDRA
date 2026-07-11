package com.interndra.service

import android.graphics.Color

/**
 * TerminalBuffer — ANSI escape sequence parser and terminal state machine.
 *
 * Processes raw shell output and produces structured [TerminalLine] objects
 * with parsed ANSI color spans for Compose rendering.
 */
class TerminalBuffer {

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

    private val maxRows = 500
    private val maxEscapeLength = 256

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
    private val currentLine = StringBuilder()
    private val currentSpans = mutableListOf<ColorSpan>()

    // ── Escape state ────────────────────────────────────────────────────
    private val escapeBuffer = StringBuilder()
    private var inEscape = false
    private var inNonCsiEscape = false

    /** Remaining chars to consume for fixed-length escapes like \033(B (3 chars total). */
    private var fixedLengthRemaining = 0

    // ── Public API ──────────────────────────────────────────────────────

    @Synchronized
    fun processOutput(raw: String) {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]

            if (inEscape) {
                processEscapeChar(ch)
            } else if (ch == '\u001b') {
                inEscape = true
                inNonCsiEscape = false
                fixedLengthRemaining = 0
                escapeBuffer.clear()
                escapeBuffer.append(ch)
            } else if (ch == '\r') {
                cursorCol = 0
            } else if (ch == '\n') {
                commitLine()
            } else if (ch == '\b') {
                if (currentLine.isNotEmpty()) {
                    currentLine.deleteCharAt(currentLine.length - 1)
                    trimSpansToLength(currentLine.length)
                    cursorCol = (cursorCol - 1).coerceAtLeast(0)
                }
            } else if (ch == '\t') {
                val spaces = 8 - (cursorCol % 8)
                appendText(" ".repeat(spaces))
            } else if (ch.isISOControl()) {
                // Skip other control chars
            } else {
                appendText(ch.toString())
            }

            i++
        }
    }

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

    @Synchronized
    fun flushPlainText(): String = flush().joinToString("\n") { it.text }

    @Synchronized
    fun reset() {
        cursorRow = 0; cursorCol = 0
        currentLine.clear(); currentSpans.clear()
        screenBuffer.clear()
        escapeBuffer.clear()
        inEscape = false; inNonCsiEscape = false; fixedLengthRemaining = 0
        currentFg = null; currentBg = null
        currentBold = false; currentDim = false; currentUnderline = false
    }

    // ── Escape state machine ────────────────────────────────────────────

    private fun processEscapeChar(ch: Char) {
        // ── Fixed-length escape (e.g. \033(B is exactly 3 chars) ────
        if (fixedLengthRemaining > 0) {
            escapeBuffer.append(ch)
            fixedLengthRemaining--
            if (fixedLengthRemaining == 0) {
                // All consumed — silently discard
                finalizeEscape()
            }
            return
        }

        // ── Escape buffer overflow protection ────────────────────────
        if (escapeBuffer.length >= maxEscapeLength) {
            // Just eat chars until we see a final byte, then exit cleanly
            val isFinalByte = ch in 'A'..'Z' || ch in 'a'..'z' ||
                ch == '@' || ch == '`' || ch == '{' || ch == '}' || ch == '~' || ch == '\u0007'
            if (isFinalByte) {
                finalizeEscape()
            }
            return
        }

        escapeBuffer.append(ch)

        // ── Detect escape type on the second character ───────────────
        if (escapeBuffer.length == 2) {
            when (ch) {
                '[' -> inNonCsiEscape = false  // CSI sequence
                ']' -> inNonCsiEscape = true   // OSC sequence
                '(' -> { /* G0 charset select — 3 chars total: \033(X */ fixedLengthRemaining = 1 }
                ')' -> { /* G1 charset select — 3 chars total: \033)X */ fixedLengthRemaining = 1 }
                'X' -> inNonCsiEscape = true   // SOS (Start of String)
                '^' -> inNonCsiEscape = true   // PM (Privacy Message)
                '_' -> inNonCsiEscape = true   // APC (Application Program Command)
                'P' -> inNonCsiEscape = true   // DCS (Device Control String)
                'k' -> inNonCsiEscape = true   // Linux private escape
                else -> {
                    // Single two-char escape like \033=, \033>, \0337, \0338, \033c
                    if (ch in '\u0020'..'\u002f' || ch in '\u0030'..'\u007e') {
                        finalizeEscape()
                    }
                }
            }
            // For `(` and `)`, we'll consume 1 more char via fixedLengthRemaining
            if ((ch == '(' || ch == ')') && fixedLengthRemaining == 0) {
                finalizeEscape()
            }
            return
        }

        // ── Handle based on escape type ──────────────────────────────
        if (inNonCsiEscape) {
            when {
                ch == '\u0007' -> finalizeEscape()                       // BEL terminates OSC
                ch == '\u001b' && escapeBuffer.length >= 3 -> { }        // Could be ST start — wait for next
                ch == '\\' && escapeBuffer.length >= 3 && escapeBuffer[escapeBuffer.length - 2] == '\u001b' -> finalizeEscape()  // ST
            }
        } else {
            val isFinalByte = ch in 'A'..'Z' || ch in 'a'..'z' ||
                ch == '@' || ch == '`' || ch == '{' || ch == '}' || ch == '~'

            if (isFinalByte) {
                handleEscapeSequence(escapeBuffer.toString())
                finalizeEscape()
            } else if (ch == '\u0007') {
                finalizeEscape()
            }
        }
    }

    private fun finalizeEscape() {
        escapeBuffer.clear()
        inEscape = false
        inNonCsiEscape = false
        fixedLengthRemaining = 0
    }

    /** Parse params from a complete CSI sequence string. */
    private fun parseEscapeParams(seq: String): List<Int> {
        if (seq.length <= 2 || seq[1] != '[') return emptyList()
        var paramsStr = seq.drop(2).dropLast(1)
        if (paramsStr.startsWith('?')) {
            paramsStr = paramsStr.drop(1)
        }
        if (paramsStr.isEmpty()) return emptyList()
        return paramsStr.split(';')
            .filter { it.isNotEmpty() }  // Skip empty params (trailing semicolons)
            .map { it.toIntOrNull() ?: 0 }
    }

    private fun handleEscapeSequence(seq: String) {
        val final = seq.last()
        // Always parse fresh params — never reuse from previous sequence
        val p = parseEscapeParams(seq)

        when (final) {
            'm' -> handleSGR(p)
            'A' -> moveCursor(0, -(p.firstOrNull() ?: 1))
            'B' -> moveCursor(0, p.firstOrNull() ?: 1)
            'C' -> moveCursor(p.firstOrNull() ?: 1, 0)
            'D' -> moveCursor(-(p.firstOrNull() ?: 1), 0)
            'E' -> { cursorCol = 0; cursorRow += (p.firstOrNull() ?: 1) }
            'F' -> { cursorCol = 0; cursorRow = (cursorRow - (p.firstOrNull() ?: 1)).coerceAtLeast(0) }
            'G' -> { cursorCol = ((p.firstOrNull() ?: 1) - 1).coerceAtLeast(0) }
            'H', 'f' -> {
                cursorRow = ((p.getOrNull(0) ?: 1) - 1).coerceAtLeast(0)
                cursorCol = ((p.getOrNull(1) ?: 1) - 1).coerceAtLeast(0)
            }
            'J' -> when (p.firstOrNull() ?: 0) {
                0 -> eraseFromCursor()
                1 -> eraseToCursor()
                2, 3 -> { screenBuffer.clear(); currentLine.clear(); currentSpans.clear(); cursorRow = 0; cursorCol = 0 }
            }
            'K' -> when (p.firstOrNull() ?: 0) {
                0 -> { if (cursorCol < currentLine.length) { currentLine.delete(cursorCol, currentLine.length); trimSpansToLength(cursorCol) } }
                1 -> { if (cursorCol > 0 && cursorCol <= currentLine.length) { currentLine.delete(0, cursorCol); shiftSpans(-cursorCol) } }
                2 -> { currentLine.clear(); currentSpans.clear() }
            }
            'L' -> repeat(p.firstOrNull() ?: 1) { screenBuffer.add(LineData("", emptyList())) }
            'M' -> repeat((p.firstOrNull() ?: 1).coerceAtMost(screenBuffer.size)) { screenBuffer.removeAt(screenBuffer.size - 1) }
            'P' -> {
                val n = p.firstOrNull() ?: 1
                if (cursorCol < currentLine.length) {
                    val deleteEnd = (cursorCol + n).coerceAtMost(currentLine.length)
                    currentLine.delete(cursorCol, deleteEnd)
                    trimSpansAfterDelete(cursorCol, deleteEnd - cursorCol)
                }
            }
            'X' -> {
                val n = p.firstOrNull() ?: 1
                if (cursorCol < currentLine.length) {
                    val eraseEnd = (cursorCol + n).coerceAtMost(currentLine.length)
                    currentLine.replace(cursorCol, eraseEnd, " ".repeat(eraseEnd - cursorCol))
                }
            }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }
            'h', 'l' -> { /* DECSET/DECRST — silently ignored */ }
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
                4 -> currentUnderline = true
                22 -> { currentBold = false; currentDim = false }
                24 -> currentUnderline = false
                30 -> currentFg = Color.BLACK
                31 -> currentFg = Color.RED
                32 -> currentFg = Color.rgb(0, 170, 0)
                33 -> currentFg = Color.rgb(170, 170, 0)
                34 -> currentFg = Color.BLUE
                35 -> currentFg = Color.MAGENTA
                36 -> currentFg = Color.CYAN
                37 -> currentFg = Color.WHITE
                38 -> {
                    if (i + 2 < p.size && p[i + 1] == 5) {
                        currentFg = color256ToRgb(p[i + 2]); i += 2
                    } else if (i + 4 < p.size && p[i + 1] == 2) {
                        currentFg = Color.rgb(p[i + 2].coerceIn(0, 255), p[i + 3].coerceIn(0, 255), p[i + 4].coerceIn(0, 255)); i += 4
                    }
                }
                39 -> currentFg = null
                40 -> currentBg = Color.BLACK
                41 -> currentBg = Color.RED
                42 -> currentBg = Color.rgb(0, 170, 0)
                43 -> currentBg = Color.rgb(170, 170, 0)
                44 -> currentBg = Color.BLUE
                45 -> currentBg = Color.MAGENTA
                46 -> currentBg = Color.CYAN
                47 -> currentBg = Color.WHITE
                48 -> {
                    if (i + 2 < p.size && p[i + 1] == 5) {
                        currentBg = color256ToRgb(p[i + 2]); i += 2
                    } else if (i + 4 < p.size && p[i + 1] == 2) {
                        currentBg = Color.rgb(p[i + 2].coerceIn(0, 255), p[i + 3].coerceIn(0, 255), p[i + 4].coerceIn(0, 255)); i += 4
                    }
                }
                49 -> currentBg = null
                90 -> currentFg = Color.DKGRAY
                91 -> currentFg = Color.rgb(255, 100, 100)
                92 -> currentFg = Color.rgb(100, 255, 100)
                93 -> currentFg = Color.YELLOW
                94 -> currentFg = Color.rgb(100, 100, 255)
                95 -> currentFg = Color.rgb(255, 100, 255)
                96 -> currentFg = Color.rgb(100, 255, 255)
                97 -> currentFg = Color.LTGRAY
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

    private fun color256ToRgb(index: Int): Int = when {
        index < 0 -> Color.BLACK
        index < 16 -> ansiToRgb(index)
        index < 232 -> {
            val ci = index - 16
            val r = (ci / 36) % 6; val g = (ci / 6) % 6; val b = ci % 6
            fun scale(v: Int) = if (v == 0) 0 else v * 40 + 55
            Color.rgb(scale(r), scale(g), scale(b))
        }
        index < 256 -> { val gray = (index - 232) * 10 + 8; Color.rgb(gray, gray, gray) }
        else -> Color.WHITE
    }

    private fun ansiToRgb(index: Int): Int = when (index) {
        0 -> Color.BLACK; 1 -> Color.RED; 2 -> Color.rgb(0, 170, 0); 3 -> Color.rgb(170, 170, 0)
        4 -> Color.BLUE; 5 -> Color.MAGENTA; 6 -> Color.CYAN; 7 -> Color.LTGRAY
        8 -> Color.DKGRAY; 9 -> Color.rgb(255, 100, 100); 10 -> Color.rgb(100, 255, 100); 11 -> Color.YELLOW
        12 -> Color.rgb(100, 100, 255); 13 -> Color.rgb(255, 100, 255); 14 -> Color.rgb(100, 255, 255); 15 -> Color.WHITE
        else -> Color.WHITE
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
        while (screenBuffer.size > cursorRow + 1) {
            screenBuffer.removeAt(screenBuffer.size - 1)
        }
    }

    private fun eraseToCursor() {
        if (cursorCol > 0 && cursorCol <= currentLine.length) {
            currentLine.delete(0, cursorCol)
            shiftSpans(-cursorCol)
        }
        while (cursorRow > 0 && screenBuffer.size >= cursorRow) {
            screenBuffer.removeAt(0)
        }
    }

    // ── Text and span management ─────────────────────────────────────────

    private fun appendText(text: String) {
        val start = cursorCol

        if (cursorCol <= currentLine.length) {
            val before = currentLine.substring(0, cursorCol)
            val afterLen = (cursorCol + text.length).coerceAtMost(currentLine.length)
            val after = currentLine.substring(afterLen)
            val overwriteEnd = cursorCol + text.length

            // Trim spans that overlap with the overwritten region
            trimSpansInRange(cursorCol, overwriteEnd)

            currentLine.clear()
            currentLine.append(before)
            currentLine.append(text)
            currentLine.append(after)
        } else {
            currentLine.append(text)
        }
        cursorCol += text.length

        if (currentFg != null || currentBg != null || currentBold || currentDim || currentUnderline) {
            currentSpans.add(ColorSpan(
                start = start, end = cursorCol,
                fgColor = currentFg, bgColor = currentBg,
                bold = currentBold, dim = currentDim, underline = currentUnderline
            ))
        }
    }

    private fun commitLine() {
        screenBuffer.add(LineData(currentLine.toString(), currentSpans.toList()))
        while (screenBuffer.size > maxRows) screenBuffer.removeAt(0)
        currentLine.clear()
        currentSpans.clear()
        cursorRow++
        cursorCol = 0
    }

    /** Trim spans that extend beyond `newLength`. */
    private fun trimSpansToLength(newLength: Int) {
        val iter = currentSpans.iterator()
        while (iter.hasNext()) {
            val span = iter.next()
            when {
                span.start >= newLength -> iter.remove()
                span.end > newLength -> currentSpans[currentSpans.indexOf(span)] = span.copy(end = newLength)
            }
        }
    }

    /**
     * Remove or truncate spans that overlap with [rangeStart, rangeEnd).
     * Shifting logic is removed — spans after the overwritten range keep their
     * original positions (text after overwrite doesn't shift).
     */
    private fun trimSpansInRange(rangeStart: Int, rangeEnd: Int) {
        val iter = currentSpans.iterator()
        while (iter.hasNext()) {
            val span = iter.next()
            when {
                // Span entirely within overwrite range — remove
                span.start >= rangeStart && span.end <= rangeEnd -> iter.remove()
                // Span starts within overwrite range, ends after — truncate start to rangeEnd
                span.start >= rangeStart && span.start < rangeEnd -> {
                    val idx = currentSpans.indexOf(span)
                    currentSpans[idx] = span.copy(start = rangeEnd)
                }
                // Span starts before, ends within overwrite range — truncate end
                span.start < rangeStart && span.end > rangeStart -> {
                    val idx = currentSpans.indexOf(span)
                    currentSpans[idx] = span.copy(end = rangeStart)
                }
            }
        }
    }

    /** Shift all spans by `delta` (for EL 1K erase). */
    private fun shiftSpans(delta: Int) {
        val iter = currentSpans.iterator()
        while (iter.hasNext()) {
            val span = iter.next()
            val newStart = span.start + delta
            val newEnd = span.end + delta
            if (newEnd <= 0) {
                iter.remove()
            } else {
                val idx = currentSpans.indexOf(span)
                currentSpans[idx] = span.copy(
                    start = newStart.coerceAtLeast(0),
                    end = newEnd.coerceAtLeast(1)
                )
            }
        }
    }

    /** Trim spans after DCH deletes characters. */
    private fun trimSpansAfterDelete(start: Int, count: Int) {
        val iter = currentSpans.iterator()
        while (iter.hasNext()) {
            val span = iter.next()
            when {
                span.start >= start + count -> {
                    val idx = currentSpans.indexOf(span)
                    currentSpans[idx] = span.copy(start = span.start - count, end = span.end - count)
                }
                span.start >= start && span.start < start + count -> {
                    if (span.end <= start + count) iter.remove()
                    else { val idx = currentSpans.indexOf(span); currentSpans[idx] = span.copy(start = start, end = span.end - count) }
                }
                span.end > start && span.end <= start + count -> {
                    val idx = currentSpans.indexOf(span); currentSpans[idx] = span.copy(end = start)
                }
                span.start < start && span.end > start + count -> {
                    val idx = currentSpans.indexOf(span); currentSpans[idx] = span.copy(end = span.end - count)
                }
            }
        }
    }

    /** Merge overlapping color spans with identical attributes. */
    private fun mergeSpans(spans: List<ColorSpan>): List<ColorSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.start }
        val merged = mutableListOf<ColorSpan>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
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

    private data class LineData(val text: String, val spans: List<ColorSpan>)
}
