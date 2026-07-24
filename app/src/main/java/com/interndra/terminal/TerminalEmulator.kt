/*
 * TerminalEmulator.kt — ANSI/VT100 escape code parser and terminal screen state.
 *
 * A lightweight state-machine-based parser that processes terminal byte streams
 * and maintains:
 *   - Screen buffer (rows x cols of characters with attributes)
 *   - Cursor position
 *   - Scrollback buffer (history)
 *   - Text attributes (bold, italic, colors)
 *
 * Supported control sequences:
 *   - ESC [ ... m   — SGR (colors, bold, italic, underline)
 *   - ESC [ A/B/C/D — Cursor movement (up/down/left/right)
 *   - ESC [ H/f     — Cursor home/position
 *   - ESC [ J       — Erase display (partial/full)
 *   - ESC [ K       — Erase line
 *   - ESC [ s/u     — Save/restore cursor
 *   - ESC 7/8       — Save/restore cursor (DEC)
 *   - ESC ( B       — Character set selection
 *   - \r, \n, \t, \b — Basic control chars
 *
 * This is a simplified but functional implementation. For production use,
 * consider using Termux's terminal-emulator library for full VT100/xterm
 * compatibility.
 */

package com.interndra.terminal

import android.util.Log

/**
 * ANSI/VT100 terminal emulator.
 *
 * @param rows    Initial number of rows (height)
 * @param columns Initial number of columns (width)
 */
class TerminalEmulator(
    var rows: Int = DEFAULT_ROWS,
    var columns: Int = DEFAULT_COLUMNS
) {
    companion object {
        private const val TAG = "TerminalEmulator"

        const val DEFAULT_ROWS = 40
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_SCROLLBACK_SIZE = 2000

        // SGR color constants
        const val COLOR_BLACK   = 0
        const val COLOR_RED     = 1
        const val COLOR_GREEN   = 2
        const val COLOR_YELLOW  = 3
        const val COLOR_BLUE    = 4
        const val COLOR_MAGENTA = 5
        const val COLOR_CYAN    = 6
        const val COLOR_WHITE   = 7
        const val COLOR_DEFAULT = 9
    }

    // ── Screen cell ─────────────────────────────────────────────────────

    /** A single character cell on the terminal screen. */
    data class Cell(
        var char: Char = ' ',
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strikethrough: Boolean = false,
        var blink: Boolean = false,
        var reverse: Boolean = false,
        var foreground: Int = COLOR_DEFAULT,
        var background: Int = COLOR_DEFAULT
    )

    // ── State ───────────────────────────────────────────────────────────

    /** The main screen buffer. Mutable — recreated on [resize]. */
    private var screenBuffer = Array(rows) { Array(columns) { Cell() } }

    /** Scrollback buffer (history lines). */
    private val scrollbackBuffer = mutableListOf<Array<Cell>>()
    private val maxScrollback = DEFAULT_SCROLLBACK_SIZE

    /** Current cursor position. */
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    /** Current text attributes (SGR state). */
    private var bold = false
    private var italic = false
    private var underline = false
    private var strikethrough = false
    private var blink = false
    private var reverse = false
    private var foreground = COLOR_DEFAULT
    private var background = COLOR_DEFAULT

    /** Saved cursor position (for ESC [ s / ESC 7). */
    private var savedRow = 0
    private var savedCol = 0

    /** State machine state. */
    private enum class ParserState {
        NORMAL,
        ESC,
        CSI,       // ESC [
        CSI_PARAM, // ESC [ <digits>
        OSC,       // ESC ]
        OSC_STRING // ESC ] ... ST
    }

    private var parserState = ParserState.NORMAL
    private val csiParams = mutableListOf<Int>()
    private var csiParamBuilder = StringBuilder()
    private var oscStringBuilder = StringBuilder()

    /** Whether the screen has been modified since last check. */
    var isDirty: Boolean = false
        private set

    /** Callback invoked when the screen is modified. */
    var onScreenChanged: (() -> Unit)? = null

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Process a single byte from the terminal output stream.
     *
     * @param b  The byte value (0-255)
     */
    fun processByte(b: Int) {
        when (parserState) {
            ParserState.NORMAL -> processNormalByte(b)
            ParserState.ESC -> processEscByte(b)
            ParserState.CSI -> processCsiByte(b)
            ParserState.CSI_PARAM -> processCsiParamByte(b)
            ParserState.OSC -> processOscByte(b)
            ParserState.OSC_STRING -> processOscStringByte(b)
        }
    }

    /**
     * Process multiple bytes at once.
     */
    fun processBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (i in offset until (offset + length).coerceAtMost(bytes.size)) {
            processByte(bytes[i].toInt() and 0xFF)
        }
    }

    /**
     * Process a UTF-8 string directly.
     */
    fun processString(text: String) {
        for (c in text) {
            processByte(c.code)
        }
    }

    /**
     * Get the character at a screen position.
     */
    fun getCell(row: Int, col: Int): Cell {
        if (row < 0 || row >= rows || col < 0 || col >= columns) return Cell()
        return screenBuffer[row][col]
    }

    /**
     * Get the entire screen buffer as a list of lines.
     */
    fun getScreenLines(): List<String> {
        return screenBuffer.map { row ->
            row.joinToString("") { cell -> cell.char.toString() }
        }
    }

    /**
     * Get the screen buffer with ANSI escape codes for colors.
     */
    fun getScreenAnsi(): List<String> {
        return screenBuffer.map { row ->
            val sb = StringBuilder()
            var lastFg = COLOR_DEFAULT
            var lastBg = COLOR_DEFAULT
            var lastBold = false
            for (cell in row) {
                if (cell.foreground != lastFg || cell.background != lastBg || cell.bold != lastBold) {
                    sb.append("\u001B[")
                    val codes = mutableListOf<Int>()
                    if (cell.bold != lastBold) codes.add(if (cell.bold) 1 else 22)
                    if (cell.foreground != lastFg) codes.add(30 + cell.foreground)
                    if (cell.background != lastBg) codes.add(40 + cell.background)
                    sb.append(codes.joinToString(";"))
                    sb.append('m')
                    lastFg = cell.foreground
                    lastBg = cell.background
                    lastBold = cell.bold
                }
                sb.append(cell.char)
            }
            sb.append("\u001B[0m")
            sb.toString()
        }
    }

    /**
     * Get plain text from the screen (no escape codes).
     */
    fun getScreenText(): String {
        return getScreenLines().joinToString("\n")
    }

    /**
     * Get scrollback content (history lines).
     */
    fun getScrollbackLines(): List<String> {
        return scrollbackBuffer.map { row ->
            row.joinToString("") { cell -> cell.char.toString() }
        }
    }

    /**
     * Get the full content including scrollback.
     */
    fun getFullContent(): String {
        val full = mutableListOf<String>()
        full.addAll(getScrollbackLines())
        full.addAll(getScreenLines())
        return full.joinToString("\n")
    }

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newColumns: Int) {
        if (newRows == rows && newColumns == columns) return

        val oldRows = rows
        val oldCols = columns

        rows = newRows
        columns = newColumns

        // Recreate screen buffer with new dimensions, copying old content where it fits
        val newBuffer = Array(rows) { r ->
            Array(columns) { c ->
                if (r < oldRows && c < oldCols) {
                    screenBuffer[r][c].copy()
                } else {
                    Cell()
                }
            }
        }
        screenBuffer = newBuffer

        // Adjust cursor
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)

        markDirty()
    }

    /** Clear the entire screen and home the cursor. */
    fun clearScreen() {
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                screenBuffer[r][c] = Cell()
            }
        }
        cursorRow = 0
        cursorCol = 0
        markDirty()
    }

    /** Reset all attributes to default. */
    fun resetAttributes() {
        bold = false
        italic = false
        underline = false
        strikethrough = false
        blink = false
        reverse = false
        foreground = COLOR_DEFAULT
        background = COLOR_DEFAULT
    }

    // ── Internal byte processing ────────────────────────────────────────

    private fun processNormalByte(b: Int) {
        when (b) {
            0x00 -> { /* NOP */ }
            0x07 -> { /* BEL - ignore */ }
            0x08 -> { /* BS - backspace */ if (cursorCol > 0) cursorCol--; markDirty() }
            0x09 -> { /* HT - tab */ val tabStop = 8; cursorCol = ((cursorCol / tabStop) + 1) * tabStop; if (cursorCol >= columns) { cursorCol = 0; newLine() }; markDirty() }
            0x0A -> { /* LF - line feed */ newLine(); markDirty() }
            0x0B -> { /* VT - vertical tab */ newLine(); markDirty() }
            0x0C -> { /* FF - form feed */ clearScreen(); markDirty() }
            0x0D -> { /* CR - carriage return */ cursorCol = 0; markDirty() }
            0x1B -> { /* ESC */ parserState = ParserState.ESC }
            in 0x20..0x7E -> { /* Printable ASCII */ putChar(b.toChar()); markDirty() }
            in 0x80..0xBF -> { /* UTF-8 continuation byte — pass through as fallback */ putChar(b.toChar()); markDirty() }
            in 0xC0..0xDF -> { /* 2-byte UTF-8 start — pass through */ putChar(b.toChar()); markDirty() }
            in 0xE0..0xEF -> { /* 3-byte UTF-8 start — pass through */ putChar(b.toChar()); markDirty() }
            in 0xF0..0xF7 -> { /* 4-byte UTF-8 start — pass through */ putChar(b.toChar()); markDirty() }
            else -> { /* Unknown control char */ }
        }
    }

    private fun processEscByte(b: Int) {
        parserState = ParserState.NORMAL
        when (b) {
            '['.code -> parserState = ParserState.CSI
            ']'.code -> {
                parserState = ParserState.OSC
                oscStringBuilder = StringBuilder()
            }
            '7'.code -> { /* DECSC - Save cursor */ savedRow = cursorRow; savedCol = cursorCol }
            '8'.code -> { /* DECRC - Restore cursor */ cursorRow = savedRow; cursorCol = savedCol; markDirty() }
            'D'.code -> { /* IND - Index (scroll down) */ newLine() }
            'M'.code -> { /* RI - Reverse index (scroll up) */ reverseIndex(); markDirty() }
            'c'.code -> { /* RIS - Reset to initial state */ resetAttributes(); clearScreen() }
            '('.code -> { /* G0 character set - ignore */ }
            ')'.code -> { /* G1 character set - ignore */ }
            '*'.code -> { /* G2 character set - ignore */ }
            '+'.code -> { /* G3 character set - ignore */ }
            else -> { /* Unknown escape - ignore */ }
        }
    }

    private fun processCsiByte(b: Int) {
        csiParams.clear()
        csiParamBuilder = StringBuilder()
        parserState = ParserState.CSI_PARAM
        processCsiParamByte(b)
    }

    private fun processCsiParamByte(b: Int) {
        when {
            b == ';'.code -> {
                val param = csiParamBuilder.toString().toIntOrNull() ?: 0
                csiParams.add(param)
                csiParamBuilder = StringBuilder()
            }
            b in '0'.code..'9'.code -> {
                csiParamBuilder.append(b.toChar())
            }
            b in '@'.code..'~'.code || b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code -> {
                // Final byte
                if (csiParamBuilder.isNotEmpty()) {
                    val param = csiParamBuilder.toString().toIntOrNull() ?: 0
                    csiParams.add(param)
                }
                if (csiParams.isEmpty()) csiParams.add(0)
                executeCsiCommand(b.toChar())
                parserState = ParserState.NORMAL
            }
            else -> {
                // Unknown character - abort
                parserState = ParserState.NORMAL
            }
        }
    }

    private fun processOscByte(b: Int) {
        when (b) {
            '0'.code, '1'.code, '2'.code, '3'.code, '4'.code,
            '5'.code, '6'.code, '7'.code, '8'.code, '9'.code,
            ';'.code -> {
                oscStringBuilder.append(b.toChar())
                parserState = ParserState.OSC_STRING
            }
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun processOscStringByte(b: Int) {
        when {
            b == 0x07 || b == '\\'.code -> {
                // ST (String Terminator) - BEL or ESC \
                parserState = ParserState.NORMAL
                handleOsc(oscStringBuilder.toString())
            }
            b == 0x1B -> {
                // ESC - might be ESC \ terminator
                parserState = ParserState.ESC
            }
            else -> {
                oscStringBuilder.append(b.toChar())
            }
        }
    }

    // ── Command execution ───────────────────────────────────────────────

    private fun executeCsiCommand(cmd: Char) {
        val p = csiParams.toList() // copy
        when (cmd) {
            'A' -> { /* CUU - Cursor Up */ cursorRow = maxOf(0, cursorRow - (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); markDirty() }
            'B' -> { /* CUD - Cursor Down */ cursorRow = minOf(rows - 1, cursorRow + (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); markDirty() }
            'C' -> { /* CUF - Cursor Forward */ cursorCol = minOf(columns - 1, cursorCol + (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); markDirty() }
            'D' -> { /* CUB - Cursor Back */ cursorCol = maxOf(0, cursorCol - (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); markDirty() }
            'E' -> { /* CNL - Cursor Next Line */ cursorRow = minOf(rows - 1, cursorRow + (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); cursorCol = 0; markDirty() }
            'F' -> { /* CPL - Cursor Previous Line */ cursorRow = maxOf(0, cursorRow - (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); cursorCol = 0; markDirty() }
            'G' -> { /* CHA - Cursor Horizontal Absolute */ cursorCol = (p.firstOrNull()?.coerceIn(1, columns) ?: 1) - 1; markDirty() }
            'H', 'f' -> { /* CUP - Cursor Position */ val r = p.getOrElse(0) { 1 }.coerceIn(1, rows) - 1; val c = p.getOrElse(1) { 1 }.coerceIn(1, columns) - 1; cursorRow = r; cursorCol = c; markDirty() }
            'J' -> { /* ED - Erase Display */ val mode = p.firstOrNull() ?: 0; when (mode) { 0 -> eraseFromCursor(); 1 -> eraseToCursor(); 2 -> clearScreen(); 3 -> clearScreen() }; markDirty() }
            'K' -> { /* EL - Erase Line */ val mode = p.firstOrNull() ?: 0; when (mode) { 0 -> eraseLineFromCursor(); 1 -> eraseLineToCursor(); 2 -> eraseEntireLine() }; markDirty() }
            'L' -> { /* IL - Insert Line */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; insertLines(n); markDirty() }
            'M' -> { /* DL - Delete Line */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; deleteLines(n); markDirty() }
            'P' -> { /* DCH - Delete Character */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; deleteChars(n); markDirty() }
            '@' -> { /* ICH - Insert Character */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; insertChars(n); markDirty() }
            'X' -> { /* ECH - Erase Character */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; eraseChars(n); markDirty() }
            'S' -> { /* SU - Scroll Up */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; scrollUp(n); markDirty() }
            'T' -> { /* SD - Scroll Down */ val n = p.firstOrNull()?.coerceAtLeast(1) ?: 1; scrollDown(n); markDirty() }
            's' -> { /* Save cursor */ savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { /* Restore cursor */ cursorRow = savedRow; cursorCol = savedCol; markDirty() }
            'm' -> { /* SGR - Select Graphic Rendition */ handleSgr(p) }
            'h' -> { /* SM - Set Mode */ /* handle DEC private modes if needed */ }
            'l' -> { /* RM - Reset Mode */ /* handle DEC private modes if needed */ }
            'n' -> { /* DSR - Device Status Report */ /* would need to send response */ }
            else -> { /* Unknown CSI */ }
        }
    }

    private fun handleSgr(params: List<Int>) {
        if (params.isEmpty() || params.first() == 0) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> resetAttributes()
                1 -> bold = true
                3 -> italic = true
                4 -> underline = true
                7 -> reverse = true
                9 -> strikethrough = true
                5, 6 -> blink = true
                22 -> bold = false
                23 -> italic = false
                24 -> underline = false
                25 -> blink = false
                27 -> reverse = false
                29 -> strikethrough = false
                30, 31, 32, 33, 34, 35, 36, 37 -> foreground = p - 30
                38 -> { /* 256/true color foreground - skip for simplicity */ if (i + 1 < params.size && params[i + 1] == 5) i += 2; else if (i + 1 < params.size && params[i + 1] == 2) i += 4 }
                39 -> foreground = COLOR_DEFAULT
                40, 41, 42, 43, 44, 45, 46, 47 -> background = p - 40
                48 -> { /* 256/true color background - skip */ if (i + 1 < params.size && params[i + 1] == 5) i += 2; else if (i + 1 < params.size && params[i + 1] == 2) i += 4 }
                49 -> background = COLOR_DEFAULT
                90, 91, 92, 93, 94, 95, 96, 97 -> foreground = p - 82  /* bright fg */
                100, 101, 102, 103, 104, 105, 106, 107 -> background = p - 92  /* bright bg */
            }
            i++
        }
        markDirty()
    }

    private fun handleOsc(os: String) {
        // Handle OSC sequences (e.g., OSC 0 ; title ST)
        // For simplicity, we mostly ignore these
        if (os.startsWith("0;") || os.startsWith("1;") || os.startsWith("2;")) {
            // Set window title - ignore
        }
    }

    // ── Screen operations ───────────────────────────────────────────────

    private fun putChar(c: Char) {
        if (cursorRow >= rows) {
            // Should not happen - newLine handles scrolling
            cursorRow = rows - 1
        }

        val cell = screenBuffer[cursorRow][cursorCol]
        cell.char = c
        cell.bold = bold
        cell.italic = italic
        cell.underline = underline
        cell.strikethrough = strikethrough
        cell.blink = blink
        cell.reverse = reverse
        cell.foreground = foreground
        cell.background = background

        cursorCol++
        if (cursorCol >= columns) {
            cursorCol = 0
            newLine()
        }
    }

    private fun newLine() {
        cursorRow++
        if (cursorRow >= rows) {
            // Scroll the screen up
            scrollUp(1)
            cursorRow = rows - 1
            // Clear the new line
            for (c in 0 until columns) {
                screenBuffer[cursorRow][c] = Cell()
            }
        }
    }

    private fun reverseIndex() {
        if (cursorRow > 0) {
            cursorRow--
        } else {
            scrollDown(1)
        }
    }

    private fun scrollUp(n: Int) {
        val count = n.coerceAtLeast(1)
        // Move lines to scrollback
        for (i in 0 until count) {
            if (i < rows) {
                scrollbackBuffer.add(screenBuffer[i].copyOf())
                if (scrollbackBuffer.size > maxScrollback) {
                    scrollbackBuffer.removeAt(0)
                }
            }
        }
        // Shift rows up
        for (r in 0 until (rows - count)) {
            screenBuffer[r] = screenBuffer[r + count]
        }
        // Clear bottom rows
        for (r in (rows - count) until rows) {
            screenBuffer[r] = Array(columns) { Cell() }
        }
    }

    private fun scrollDown(n: Int) {
        val count = n.coerceAtLeast(1)
        // Shift rows down
        for (r in (rows - 1) downTo count) {
            screenBuffer[r] = screenBuffer[r - count]
        }
        // Clear top rows
        for (r in 0 until count) {
            screenBuffer[r] = Array(columns) { Cell() }
        }
    }

    private fun eraseFromCursor() {
        // Erase from cursor to end of screen
        eraseLineFromCursor()
        for (r in (cursorRow + 1) until rows) {
            for (c in 0 until columns) {
                screenBuffer[r][c] = Cell()
            }
        }
    }

    private fun eraseToCursor() {
        // Erase from start of screen to cursor
        for (r in 0 until cursorRow) {
            for (c in 0 until columns) {
                screenBuffer[r][c] = Cell()
            }
        }
        eraseLineToCursor()
    }

    private fun eraseLineFromCursor() {
        for (c in cursorCol until columns) {
            screenBuffer[cursorRow][c] = Cell()
        }
    }

    private fun eraseLineToCursor() {
        for (c in 0..cursorCol) {
            screenBuffer[cursorRow][c] = Cell()
        }
    }

    private fun eraseEntireLine() {
        for (c in 0 until columns) {
            screenBuffer[cursorRow][c] = Cell()
        }
    }

    private fun eraseChars(n: Int) {
        val count = n.coerceAtMost(columns - cursorCol)
        for (i in 0 until count) {
            val c = cursorCol + i
            if (c < columns) screenBuffer[cursorRow][c] = Cell()
        }
    }

    private fun insertLines(n: Int) {
        val count = n.coerceAtLeast(1)
        val bottomStart = (rows - count).coerceAtLeast(0)
        // Move existing content down
        for (r in (bottomStart - 1) downTo cursorRow) {
            if (r + count < rows) {
                screenBuffer[r + count] = screenBuffer[r]
            }
        }
        // Clear inserted lines
        for (r in cursorRow until (cursorRow + count).coerceAtMost(rows)) {
            screenBuffer[r] = Array(columns) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        val count = n.coerceAtLeast(1)
        // Shift content up
        for (r in cursorRow until (rows - count)) {
            screenBuffer[r] = screenBuffer[r + count]
        }
        // Clear bottom lines
        for (r in (rows - count) until rows) {
            screenBuffer[r] = Array(columns) { Cell() }
        }
    }

    private fun deleteChars(n: Int) {
        val count = n.coerceAtMost(columns - cursorCol)
        // Shift chars left
        for (c in cursorCol until (columns - count)) {
            screenBuffer[cursorRow][c] = screenBuffer[cursorRow][c + count]
        }
        // Clear remaining chars
        for (c in (columns - count) until columns) {
            screenBuffer[cursorRow][c] = Cell()
        }
    }

    private fun insertChars(n: Int) {
        val count = n.coerceAtMost(columns - cursorCol)
        // Shift chars right
        for (c in (columns - 1) downTo (cursorCol + count)) {
            screenBuffer[cursorRow][c] = screenBuffer[cursorRow][c - count]
        }
        // Clear inserted positions
        for (c in cursorCol until (cursorCol + count)) {
            screenBuffer[cursorRow][c] = Cell()
        }
    }

    private fun markDirty() {
        isDirty = true
        onScreenChanged?.invoke()
    }

    /** Mark screen as clean (call after rendering). */
    fun markClean() {
        isDirty = false
    }
}
