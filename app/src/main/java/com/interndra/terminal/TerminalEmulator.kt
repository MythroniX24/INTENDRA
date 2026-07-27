/*
 * TerminalEmulator.kt — Full ANSI/VT100/xterm escape code parser.
 *
 * This is the "brain" of the terminal emulator. It consumes a byte stream
 * (output from the shell), interprets ANSI/VT-100 escape sequences, and
 * maintains the screen buffer (rows × columns of characters with styles).
 *
 * ## What's new vs the previous version
 * - Uses [TextStyle] (Int-bitfield) instead of a `Cell` data class
 * - Uses [TerminalRow] for efficient row-level operations
 * - Uses [WcWidth] for correct CJK/emoji character display
 * - **Alternate screen buffer** (ESC [ ? 1049 h / l) — required by vim, nano, tmux
 * - **256-colour + true-colour** (ESC [ 38;5;N m / 38;2;R;G;B m)
 * - **Bracketed paste mode** (ESC [ ? 2004 h / l)
 * - **Mouse reporting** (ESC [ ? 1000 h / l etc.) — stores state, caller handles
 * - **Application cursor keys** (ESC [ ? 1 h / l)
 * - Much faster rendering via style-bitfield comparison
 *
 * ## Supported control sequences (extended)
 *   - SGR: 0-107 (including 256-colour `38;5;N`, true-colour `38;2;R;G;B`)
 *   - Cursor movement: CUU, CUD, CUF, CUB, CUP, CHA, CNL, CPL, HVP, etc.
 *   - Erase: ED (0-3), EL (0-2), DCH, ICH, ECH
 *   - Scroll: SU, SD, IL, DL
 *   - Modes: DEC private modes (1, 25, 1047, 1048, 1049, 2004, etc.)
 *   - Alternate screen: ESC [ ? 1049 h / l (full save/restore)
 *   - OSC: 0 (title), 1 (icon), 2 (title)
 *   - Bracketed paste: ESC [ ? 2004 h / l
 *   - Mouse: X10, normal tracking, button-event, any-event, SGR coordinates
 */

package com.interndra.terminal

import android.util.Log

class TerminalEmulator(
    var rows: Int = DEFAULT_ROWS,
    var columns: Int = DEFAULT_COLUMNS
) {
    companion object {
        private const val TAG = "TerminalEmulator"

        const val DEFAULT_ROWS = 40
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_SCROLLBACK_SIZE = 2000

        // ANSI colour constants
        const val COLOR_BLACK   = 0
        const val COLOR_RED     = 1
        const val COLOR_GREEN   = 2
        const val COLOR_YELLOW  = 3
        const val COLOR_BLUE    = 4
        const val COLOR_MAGENTA = 5
        const val COLOR_CYAN    = 6
        const val COLOR_WHITE   = 7
        const val COLOR_DEFAULT_FG = 7
        const val COLOR_DEFAULT_BG = 0
    }

    // ── Screen buffers ──────────────────────────────────────────────────
    /** The primary screen buffer. */
    private var screenBuffer = Array(rows) { TerminalRow(columns) }

    /** The alternate screen buffer (used by vim, nano, tmux, etc.). */
    private var altBuffer: Array<TerminalRow>? = null

    /** Saved primary screen for alternate-screen mode. */
    private var savedPrimaryScreen: Array<TerminalRow>? = null
    private var savedCursorRow = 0
    private var savedCursorCol = 0

    /** Scrollback buffer (history lines from primary screen). */
    private val scrollbackBuffer = mutableListOf<TerminalRow>()
    private val maxScrollback = DEFAULT_SCROLLBACK_SIZE

    // ── Cursor state ────────────────────────────────────────────────────
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    /** Whether cursor is visible. */
    var cursorVisible: Boolean = true
        private set

    // ── SGR state ───────────────────────────────────────────────────────
    private var bold = false
    private var dim = false
    private var italic = false
    private var underline = false
    private var blink = false
    private var reverse = false
    private var strikethrough = false
    private var hidden = false
    private var foreground = COLOR_DEFAULT_FG
    private var background = COLOR_DEFAULT_BG
    /** True-colour overrides (null = use indexed colour, not set). */
    private var fgRgb: Int? = null
    private var bgRgb: Int? = null

    /** Saved cursor position (for ESC [ s / ESC 7). */
    private var savedRow = 0
    private var savedCol = 0

    // ── Terminal modes ──────────────────────────────────────────────────
    /** Whether the alternate screen is active. */
    var inAlternateScreen: Boolean = false
        private set

    /** Whether bracketed paste mode is active. */
    var bracketedPasteMode: Boolean = false
        private set

    /** Application cursor keys (DECCKM). */
    var applicationCursorKeys: Boolean = false
        private set

    /** Mouse reporting modes. */
    var mouseNormalTracking: Boolean = false     // ?1000
    var mouseButtonTracking: Boolean = false     // ?1002
    var mouseAnyEvent: Boolean = false           // ?1003
    var mouseSgrFormat: Boolean = false          // ?1006 — use SGR coordinates

    /** Scroll margins (top/bottom). null = full screen. */
    private var scrollTop: Int = 0
    private var scrollBottom: Int = rows - 1

    /** Whether the screen has been modified since last check. */
    var isDirty: Boolean = false
        private set

    /** Callback invoked when screen is modified. */
    var onScreenChanged: (() -> Unit)? = null

    // ── Parser state machine ────────────────────────────────────────────
    private enum class ParserState {
        NORMAL, ESC, CSI, CSI_PARAM, OSC, OSC_STRING, DCS, DCS_STRING
    }
    private var parserState = ParserState.NORMAL
    private val csiParams = mutableListOf<Int>()
    private var csiParamBuilder = StringBuilder()
    private var oscStringBuilder = StringBuilder()

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    fun processByte(b: Int) {
        when (parserState) {
            ParserState.NORMAL -> processNormalByte(b)
            ParserState.ESC -> processEscByte(b)
            ParserState.CSI -> processCsiByte(b)
            ParserState.CSI_PARAM -> processCsiParamByte(b)
            ParserState.OSC -> processOscByte(b)
            ParserState.OSC_STRING -> processOscStringByte(b)
            ParserState.DCS -> processDcsByte(b)
            ParserState.DCS_STRING -> processDcsStringByte(b)
        }
    }

    fun processBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (i in offset until (offset + length).coerceAtMost(bytes.size)) {
            processByte(bytes[i].toInt() and 0xFF)
        }
    }

    fun processString(text: String) {
        for (c in text) {
            processByte(c.code)
        }
    }

    /** Get current style as a TextStyle-encoded int. */
    private fun currentStyle(): Int = TextStyle.encode(
        foreground = foreground,
        background = background,
        bold = bold,
        dim = dim,
        italic = italic,
        underline = underline,
        blink = blink,
        reverse = reverse,
        strikethrough = strikethrough,
        hidden = hidden
    )

    /** Get the character at a screen position. */
    fun getChar(row: Int, col: Int): Char {
        val buf = activeBuffer()
        if (row < 0 || row >= rows || col < 0 || col >= columns) return ' '
        return buf[row].getChar(col)
    }

    /** Get the style at a screen position. */
    fun getStyle(row: Int, col: Int): Int {
        val buf = activeBuffer()
        if (row < 0 || row >= rows || col < 0 || col >= columns) return TextStyle.DEFAULT
        return buf[row].getStyle(col)
    }

    /** Get the full screen as a list of TerminalRows. */
    fun getScreenRows(): List<TerminalRow> = activeBuffer().toList()

    /** Get screen as list of char arrays (for Compose grid rendering). */
    fun getScreenChars(): List<CharArray> {
        return activeBuffer().map { row -> row.chars.copyOf() }
    }

    /** Get screen as list of style arrays (for Compose colour rendering). */
    fun getScreenStyles(): List<IntArray> {
        return activeBuffer().map { row -> row.styles.copyOf() }
    }

    /** Get screen as plain text lines. */
    fun getScreenLines(): List<String> {
        return activeBuffer().map { it.text }
    }

    /** Get screen plain text. */
    fun getScreenText(): String = getScreenLines().joinToString("\n")

    /** Get scrollback content. */
    fun getScrollbackLines(): List<String> {
        return scrollbackBuffer.map { it.text }
    }

    /** Get full content (scrollback + screen). */
    fun getFullContent(): String {
        val lines = mutableListOf<String>()
        lines.addAll(getScrollbackLines())
        lines.addAll(getScreenLines())
        return lines.joinToString("\n")
    }

    /** Resize the terminal. */
    fun resize(newRows: Int, newColumns: Int) {
        if (newRows == rows && newColumns == columns) return
        val oldRows = rows; val oldCols = columns
        rows = newRows; columns = newColumns
        scrollBottom = rows - 1

        // Resize primary screen buffer
        screenBuffer = resizeBuffer(screenBuffer, oldRows, oldCols)

        // Resize alternate buffer if it exists
        if (altBuffer != null) {
            altBuffer = resizeBuffer(altBuffer!!, oldRows, oldCols)
        }

        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
        markDirty()
    }

    /** Create a new buffer with the given dimensions, copying old content where it fits. */
    private fun resizeBuffer(
        oldBuf: Array<TerminalRow>,
        oldRows: Int,
        oldCols: Int
    ): Array<TerminalRow> {
        val newBuf = Array(rows) { r ->
            val newRow = TerminalRow(columns)
            if (r < oldRows) {
                // Copy old row content (up to min of old/new columns)
                val maxCol = minOf(oldCols, columns)
                for (c in 0 until maxCol) {
                    newRow.chars[c] = oldBuf[r].getChar(c)
                    newRow.styles[c] = oldBuf[r].getStyle(c)
                }
                newRow.isWrapped = oldBuf[r].isWrapped
            }
            newRow
        }
        return newBuf
    }

    /** Clear the entire screen. */
    fun clearScreen() {
        val buf = activeBuffer()
        for (r in 0 until rows) buf[r].clear()
        cursorRow = 0; cursorCol = 0
        markDirty()
    }

    /** Reset all SGR attributes to default. */
    fun resetAttributes() {
        bold = false; dim = false; italic = false; underline = false
        blink = false; reverse = false; strikethrough = false; hidden = false
        foreground = COLOR_DEFAULT_FG; background = COLOR_DEFAULT_BG
        fgRgb = null; bgRgb = null
    }

    /** Reset terminal to initial state. */
    fun fullReset() {
        resetAttributes()
        clearScreen()
        scrollbackBuffer.clear()
        altBuffer = null
        savedPrimaryScreen = null
        inAlternateScreen = false
        bracketedPasteMode = false
        applicationCursorKeys = false
        mouseNormalTracking = false; mouseButtonTracking = false
        mouseAnyEvent = false; mouseSgrFormat = false
        cursorVisible = true
        scrollTop = 0; scrollBottom = rows - 1
        markDirty()
    }

    fun markClean() { isDirty = false }

    // ══════════════════════════════════════════════════════════════════════
    //  INTERNAL: buffer helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun activeBuffer(): Array<TerminalRow> = altBuffer ?: screenBuffer

    // ══════════════════════════════════════════════════════════════════════
    //  STATE MACHINE
    // ══════════════════════════════════════════════════════════════════════

    private fun processNormalByte(b: Int) {
        when (b) {
            0x00 -> {}
            0x07 -> {} // BEL
            0x08 -> { if (cursorCol > 0) cursorCol--; markDirty() }
            0x09 -> { // HT — tab to next 8-col stop
                val tab = 8
                cursorCol = ((cursorCol / tab) + 1) * tab
                if (cursorCol >= columns) { cursorCol = 0; newLine() }
                markDirty()
            }
            0x0A, 0x0B, 0x0C -> { newLine(); markDirty() }
            0x0D -> { cursorCol = 0; markDirty() }
            0x1B -> { parserState = ParserState.ESC }
            in 0x20..0x7E -> { putChar(b.toChar()); markDirty() }
            else -> {
                // UTF-8 multi-byte — pass through as fallback
                putChar(b.toChar()); markDirty()
            }
        }
    }

    private fun processEscByte(b: Int) {
        parserState = ParserState.NORMAL
        when (b) {
            '['.code -> parserState = ParserState.CSI
            ']'.code -> { parserState = ParserState.OSC; oscStringBuilder = StringBuilder() }
            'P'.code -> { parserState = ParserState.DCS; oscStringBuilder = StringBuilder() }
            '7'.code -> { savedRow = cursorRow; savedCol = cursorCol }
            '8'.code -> { cursorRow = savedRow; cursorCol = savedCol; markDirty() }
            'D'.code -> newLine()        // IND
            'M'.code -> { reverseIndex(); markDirty() } // RI
            'c'.code -> fullReset()
            '('.code -> {} // G0 charset select — ignore
            ')'.code -> {} // G1
            '*'.code -> {} // G2
            '+'.code -> {} // G3
            '>'.code -> {} // DECNORMAL — ignore
            '='.code -> {} // DECAPPL — ignore
            else -> processNormalByte(b)
        }
    }

    private fun processCsiByte(b: Int) {
        csiParams.clear(); csiParamBuilder = StringBuilder()
        parserState = ParserState.CSI_PARAM
        processCsiParamByte(b)
    }

    private fun processCsiParamByte(b: Int) {
        when {
            b == ';'.code -> {
                val p = csiParamBuilder.toString().toIntOrNull() ?: 0
                csiParams.add(p); csiParamBuilder = StringBuilder()
            }
            b in '0'.code..'9'.code -> csiParamBuilder.append(b.toChar())
            b == ':'.code -> {
                // Sub-parameter separator (used by SGR 38:2:...)
                val p = csiParamBuilder.toString().toIntOrNull() ?: 0
                csiParams.add(p); csiParamBuilder = StringBuilder()
                csiParams.add(-1) // marker for colon separator
            }
            b in '@'.code..'~'.code || b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code -> {
                if (csiParamBuilder.isNotEmpty()) {
                    csiParams.add(csiParamBuilder.toString().toIntOrNull() ?: 0)
                }
                if (csiParams.isEmpty()) csiParams.add(0)
                executeCsi(b.toChar())
                parserState = ParserState.NORMAL
            }
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun processOscByte(b: Int) {
        when (b) {
            in '0'.code..'9'.code, ';'.code -> {
                oscStringBuilder.append(b.toChar())
                parserState = ParserState.OSC_STRING
            }
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun processOscStringByte(b: Int) {
        when {
            b == 0x07 || (b == '\\'.code && oscStringBuilder.endsWith("\u001B")) -> {
                // ST: BEL or ESC \
                val s = oscStringBuilder.toString().removeSuffix("\u001B")
                handleOsc(s); parserState = ParserState.NORMAL
            }
            b == 0x1B -> oscStringBuilder.append(b.toChar()) // might be ESC \
            else -> oscStringBuilder.append(b.toChar())
        }
    }

    private fun processDcsByte(b: Int) {
        parserState = ParserState.DCS_STRING
        oscStringBuilder = StringBuilder()
        when (b) {
            in '0'.code..'9'.code -> oscStringBuilder.append(b.toChar())
            ';'.code -> oscStringBuilder.append(b.toChar())
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun processDcsStringByte(b: Int) {
        if (b == 0x07 || (b == '\\'.code && oscStringBuilder.endsWith("\u001B"))) {
            parserState = ParserState.NORMAL
        } else if (b == 0x1B) {
            oscStringBuilder.append(b.toChar())
        } else {
            oscStringBuilder.append(b.toChar())
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CSI COMMAND EXECUTION
    // ══════════════════════════════════════════════════════════════════════

    private fun executeCsi(cmd: Char) {
        val p = csiParams.toList()
        when (cmd) {
            'A' -> cursorRow = maxOf(0, cursorRow - (p.firstOrNull()?.coerceAtLeast(1) ?: 1))
            'B' -> cursorRow = minOf(rows - 1, cursorRow + (p.firstOrNull()?.coerceAtLeast(1) ?: 1))
            'C' -> cursorCol = minOf(columns - 1, cursorCol + (p.firstOrNull()?.coerceAtLeast(1) ?: 1))
            'D' -> cursorCol = maxOf(0, cursorCol - (p.firstOrNull()?.coerceAtLeast(1) ?: 1))
            'E' -> { cursorRow = minOf(rows - 1, cursorRow + (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); cursorCol = 0 }
            'F' -> { cursorRow = maxOf(0, cursorRow - (p.firstOrNull()?.coerceAtLeast(1) ?: 1)); cursorCol = 0 }
            'G' -> cursorCol = (p.firstOrNull()?.coerceIn(1, columns) ?: 1) - 1
            'H', 'f' -> {
                val r = p.getOrElse(0) { 1 }.coerceIn(1, rows) - 1
                val c = p.getOrElse(1) { 1 }.coerceIn(1, columns) - 1
                cursorRow = r; cursorCol = c
            }
            'J' -> when (p.firstOrNull() ?: 0) {
                0 -> eraseFromCursor(); 1 -> eraseToCursor()
                2 -> clearScreen(); 3 -> { clearScreen(); scrollbackBuffer.clear() }
            }
            'K' -> when (p.firstOrNull() ?: 0) {
                0 -> eraseLineFromCursor(); 1 -> eraseLineToCursor(); 2 -> eraseEntireLine()
            }
            'L' -> insertLines(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            'M' -> deleteLines(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            'P' -> deleteChars(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            '@' -> insertChars(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            'X' -> eraseChars(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            'S' -> scrollUp(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            'T' -> scrollDown(p.firstOrNull()?.coerceAtLeast(1) ?: 1)
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }
            'm' -> handleSgr(p)
            'h' -> handleSetMode(p)
            'l' -> handleResetMode(p)
            'n' -> {} // DSR — would need to send response back to PTY
            'r' -> { // DECSTBM — set scroll margins
                val t = p.getOrElse(0) { 1 }.coerceIn(1, rows) - 1
                val b = p.getOrElse(1) { rows }.coerceIn(1, rows) - 1
                scrollTop = minOf(t, b); scrollBottom = maxOf(t, b)
                cursorRow = 0; cursorCol = 0
            }
        }
        markDirty()
    }

    private fun handleSgr(params: List<Int>) {
        if (params.isEmpty() || params.first() == 0) { resetAttributes(); return }

        var i = 0
        while (i < params.size) {
            val p = params[i]
            // Skip colon-separator markers (-1) in param list
            if (p == -1) { i++; continue }
            when (p) {
                0 -> resetAttributes()
                1 -> bold = true
                2 -> dim = true
                3 -> italic = true
                4 -> underline = true
                5, 6 -> blink = true
                7 -> reverse = true
                8 -> hidden = true
                9 -> strikethrough = true
                22 -> { bold = false; dim = false }
                23 -> italic = false
                24 -> underline = false
                25 -> blink = false
                27 -> reverse = false
                28 -> hidden = false
                29 -> strikethrough = false
                in 30..37 -> { foreground = p - 30; fgRgb = null }
                38 -> {
                    // Check next non-marker param
                    var next = i + 1
                    while (next < params.size && params[next] == -1) next++
                    when {
                        next < params.size && params[next] == 5 && next + 1 < params.size -> {
                            foreground = params[next + 1].coerceIn(0, 255); fgRgb = null; i = next + 1
                        }
                        next < params.size && params[next] == 2 && next + 3 < params.size -> {
                            val r = params[next + 1].coerceIn(0, 255)
                            val g = params[next + 2].coerceIn(0, 255)
                            val b = params[next + 3].coerceIn(0, 255)
                            foreground = COLOR_DEFAULT_FG
                            fgRgb = (r shl 16) or (g shl 8) or b
                            i = next + 3
                        }
                    }
                }
                39 -> { foreground = COLOR_DEFAULT_FG; fgRgb = null }
                in 40..47 -> { background = p - 40; bgRgb = null }
                48 -> {
                    var next = i + 1
                    while (next < params.size && params[next] == -1) next++
                    when {
                        next < params.size && params[next] == 5 && next + 1 < params.size -> {
                            background = params[next + 1].coerceIn(0, 255); bgRgb = null; i = next + 1
                        }
                        next < params.size && params[next] == 2 && next + 3 < params.size -> {
                            val r = params[next + 1].coerceIn(0, 255)
                            val g = params[next + 2].coerceIn(0, 255)
                            val b = params[next + 3].coerceIn(0, 255)
                            background = COLOR_DEFAULT_BG
                            bgRgb = (r shl 16) or (g shl 8) or b
                            i = next + 3
                        }
                    }
                }
                49 -> { background = COLOR_DEFAULT_BG; bgRgb = null }
                in 90..97 -> foreground = p - 82  // bright fg
                in 100..107 -> background = p - 92 // bright bg
            }
            i++
        }
        markDirty()
    }

    private fun handleSetMode(params: List<Int>) {
        var idx = 0
        while (idx < params.size) {
            val mode = params[idx]
            when (mode) {
                1 -> applicationCursorKeys = true
                25 -> cursorVisible = true
                1047, 1049 -> activateAlternateScreen()
                1048 -> { savedRow = cursorRow; savedCol = cursorCol }
                2004 -> bracketedPasteMode = true
                1000 -> mouseNormalTracking = true
                1002 -> mouseButtonTracking = true
                1003 -> mouseAnyEvent = true
                1006 -> mouseSgrFormat = true
            }
            idx++
        }
    }

    private fun handleResetMode(params: List<Int>) {
        var idx = 0
        while (idx < params.size) {
            val mode = params[idx]
            when (mode) {
                1 -> applicationCursorKeys = false
                25 -> cursorVisible = false
                1047, 1049 -> deactivateAlternateScreen()
                2004 -> bracketedPasteMode = false
                1000 -> mouseNormalTracking = false
                1002 -> mouseButtonTracking = false
                1003 -> mouseAnyEvent = false
                1006 -> mouseSgrFormat = false
            }
            idx++
        }
    }

    private fun handleOsc(os: String) {
        // OSC 0;title ST — set window title
        // OSC 1;icon ST — set icon title
        // OSC 2;title ST — set window title
        if (os.startsWith("0;") || os.startsWith("1;") || os.startsWith("2;")) {
            val title = os.substringAfter(';', "")
            onTitleChanged?.invoke(title)
        }
    }

    /** Callback for window title changes. */
    var onTitleChanged: ((String) -> Unit)? = null

    // ══════════════════════════════════════════════════════════════════════
    //  ALTERNATE SCREEN
    // ══════════════════════════════════════════════════════════════════════

    private fun activateAlternateScreen() {
        if (inAlternateScreen) return
        // Save primary screen
        savedPrimaryScreen = Array(rows) { screenBuffer[it].cloneRow() }
        savedCursorRow = cursorRow; savedCursorCol = cursorCol
        // Create alternate buffer
        altBuffer = Array(rows) { TerminalRow(columns) }
        inAlternateScreen = true
        cursorRow = 0; cursorCol = 0
        markDirty()
    }

    private fun deactivateAlternateScreen() {
        if (!inAlternateScreen) return
        altBuffer = null
        inAlternateScreen = false
        // Restore primary screen
        if (savedPrimaryScreen != null) {
            for (r in 0 until minOf(rows, savedPrimaryScreen!!.size)) {
                screenBuffer[r].copyFrom(savedPrimaryScreen!![r])
            }
            savedPrimaryScreen = null
        }
        cursorRow = savedCursorRow; cursorCol = savedCursorCol
        markDirty()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SCREEN OPERATIONS
    // ══════════════════════════════════════════════════════════════════════

    private fun putChar(c: Char) {
        if (cursorRow >= rows) { cursorRow = rows - 1 }
        val buf = activeBuffer()
        val row = buf[cursorRow]

        // Handle wide chars (CJK, emoji) — occupy 2 columns
        val w = WcWidth.charWidth(c)
        if (w == 0) return // Combining character — ignore for now (would need to combine)
        if (w == 2) {
            // Set this cell + next cell
            row.setCell(cursorCol, c, currentStyle())
            if (fgRgb != null) row.setTrueColorFg(cursorCol, fgRgb!!)
            if (bgRgb != null) row.setTrueColorBg(cursorCol, bgRgb!!)
            cursorCol++
            if (cursorCol < columns) {
                row.setCell(cursorCol, '\u0000', currentStyle()) // zero-width spacer
                cursorCol++
            }
        } else {
            row.setCell(cursorCol, c, currentStyle())
            if (fgRgb != null) row.setTrueColorFg(cursorCol, fgRgb!!)
            if (bgRgb != null) row.setTrueColorBg(cursorCol, bgRgb!!)
            cursorCol++
        }

        if (cursorCol >= columns) {
            cursorCol = 0
            newLine()
        }
    }

    private fun newLine() {
        cursorRow++
        if (cursorRow > scrollBottom) {
            scrollUpInRegion(1)
            cursorRow = scrollBottom
        }
        if (cursorRow >= rows) {
            cursorRow = rows - 1
            activeBuffer()[cursorRow].clear()
        }
    }

    private fun reverseIndex() {
        if (cursorRow > scrollTop) {
            cursorRow--
        } else {
            scrollDownInRegion(1)
        }
    }

    private fun scrollUp(n: Int) {
        val buf = activeBuffer()
        val count = n.coerceAtLeast(1)
        for (i in 0 until count) {
            if (i < rows && !inAlternateScreen) {
                scrollbackBuffer.add(buf[i].cloneRow())
                if (scrollbackBuffer.size > maxScrollback) scrollbackBuffer.removeAt(0)
            }
        }
        for (r in 0 until (rows - count)) buf[r] = buf[r + count]
        for (r in (rows - count) until rows) buf[r] = TerminalRow(columns)
    }

    private fun scrollDown(n: Int) {
        val buf = activeBuffer()
        val count = n.coerceAtLeast(1)
        for (r in (rows - 1) downTo count) buf[r] = buf[r - count]
        for (r in 0 until count) buf[r] = TerminalRow(columns)
    }

    private fun scrollUpInRegion(n: Int) {
        val buf = activeBuffer()
        val count = n.coerceAtLeast(1)
        for (i in 0 until count) {
            val r = scrollTop + i
            if (r <= scrollBottom && !inAlternateScreen) {
                scrollbackBuffer.add(buf[r].cloneRow())
                if (scrollbackBuffer.size > maxScrollback) scrollbackBuffer.removeAt(0)
            }
        }
        for (r in scrollTop until (scrollBottom - count + 1)) {
            buf[r].copyFrom(buf[r + count])
        }
        for (r in (scrollBottom - count + 1)..scrollBottom) buf[r] = TerminalRow(columns)
    }

    private fun scrollDownInRegion(n: Int) {
        val buf = activeBuffer()
        val count = n.coerceAtLeast(1)
        for (r in scrollBottom downTo (scrollTop + count)) {
            buf[r].copyFrom(buf[r - count])
        }
        for (r in scrollTop until (scrollTop + count).coerceAtMost(scrollBottom + 1)) {
            buf[r] = TerminalRow(columns)
        }
    }

    private fun eraseFromCursor() {
        eraseLineFromCursor()
        val buf = activeBuffer()
        for (r in (cursorRow + 1) until rows) buf[r].clear()
    }

    private fun eraseToCursor() {
        val buf = activeBuffer()
        for (r in 0 until cursorRow) buf[r].clear()
        eraseLineToCursor()
    }

    private fun eraseLineFromCursor() {
        val row = activeBuffer()[cursorRow]
        for (c in cursorCol until columns) row.setCell(c, ' ', TextStyle.DEFAULT)
    }

    private fun eraseLineToCursor() {
        val row = activeBuffer()[cursorRow]
        for (c in 0..cursorCol) row.setCell(c, ' ', TextStyle.DEFAULT)
    }

    private fun eraseEntireLine() {
        activeBuffer()[cursorRow].clear()
    }

    private fun eraseChars(n: Int) {
        val row = activeBuffer()[cursorRow]
        val count = n.coerceAtMost(columns - cursorCol)
        for (i in 0 until count) {
            val c = cursorCol + i
            if (c < columns) row.setCell(c, ' ', TextStyle.DEFAULT)
        }
    }

    private fun insertLines(n: Int) {
        val buf = activeBuffer()
        val count = n.coerceAtLeast(1)
        val end = scrollBottom
        for (r in (end - count) downTo cursorRow) {
            if (r + count <= end) buf[r + count].copyFrom(buf[r])
        }
        for (r in cursorRow until (cursorRow + count).coerceAtMost(end + 1)) {
            buf[r] = TerminalRow(columns)
        }
    }

    private fun deleteLines(n: Int) {
        val buf = activeBuffer()
        val count = n.coerceAtLeast(1)
        for (r in cursorRow until (scrollBottom - count)) {
            buf[r].copyFrom(buf[r + count])
        }
        for (r in (scrollBottom - count + 1)..scrollBottom) {
            buf[r] = TerminalRow(columns)
        }
    }

    private fun deleteChars(n: Int) {
        val row = activeBuffer()[cursorRow]
        val count = n.coerceAtMost(columns - cursorCol)
        for (c in cursorCol until (columns - count)) {
            row.setCell(c, row.getChar(c + count), row.getStyle(c + count))
        }
        for (c in (columns - count) until columns) {
            row.setCell(c, ' ', TextStyle.DEFAULT)
        }
    }

    private fun insertChars(n: Int) {
        val row = activeBuffer()[cursorRow]
        val count = n.coerceAtMost(columns - cursorCol)
        for (c in (columns - 1) downTo (cursorCol + count)) {
            row.setCell(c, row.getChar(c - count), row.getStyle(c - count))
        }
        for (c in cursorCol until (cursorCol + count)) {
            row.setCell(c, ' ', TextStyle.DEFAULT)
        }
    }

    private fun markDirty() {
        isDirty = true
        onScreenChanged?.invoke()
    }
}
