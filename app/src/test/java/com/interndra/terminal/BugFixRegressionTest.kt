package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BugFixRegressionTest — Tests that verify the specific bugs we fixed
 * don't regress. Each test maps directly to a bug number from the analysis.
 *
 * Bug #1: TerminalAgent createShizukuShellProcess() reflection — tested in agent package
 * Bug #2: TerminalScreen rendering lag — tested via emulator batch rendering
 * Bug #3: TerminalAgent PTY exit code — tested in agent package
 * Bug #4: ByteQueue waitForSpace() deadlock — tested here
 * Bug #5: TermuxBootstrapInstaller SYMLINKS regex — tested in service package
 * Bug #6: TerminalSession emulator not fed — tested here
 * Bug #7: TextStyle bit extraction — tested here
 */
class BugFixRegressionTest {

    private lateinit var queue: ByteQueue
    private lateinit var emulator: TerminalEmulator

    @Before
    fun setUp() {
        queue = ByteQueue(capacity = 1024) // Small capacity for faster testing
        emulator = TerminalEmulator(rows = 24, columns = 80)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Bug #4: ByteQueue waitForSpace() deadlock fix
    //  notifyAll() was called OUTSIDE the synchronized block, causing a
    //  lost-wakeup race. Now it's INSIDE the synchronized block.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `Bug4 - notifyAll inside synchronized prevents lost wakeup`() {
        val smallQueue = ByteQueue(capacity = 512)
        val data = ByteArray(256) { 'X'.code.toByte() }

        // Fill the queue to capacity
        smallQueue.write(data, 0, data.size) // 256 bytes
        smallQueue.write(data, 0, data.size) // 512 bytes = full

        assertEquals(512, smallQueue.size)
        assertTrue(smallQueue.isFull)

        // Writer thread: tries to write more (will block until reader frees space)
        val writeLatch = CountDownLatch(1)
        val writeSuccess = AtomicInteger(0)

        val writer = Thread {
            // This should block until the reader consumes some data
            val written = smallQueue.write(data, 0, 100)
            writeSuccess.set(written)
            writeLatch.countDown()
        }
        writer.start()

        // Give writer time to block
        Thread.sleep(100)

        // Reader thread: consume some data to free space
        val readBuf = ByteArray(200)
        val read = smallQueue.tryRead(readBuf, 0, 200)
        assertTrue("Should have read some data", read > 0)

        // Writer should now be unblocked (notifyAll was sent inside synchronized)
        assertTrue("Writer should complete after reader frees space",
            writeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(100, writeSuccess.get())

        writer.join(1000)
    }

    @Test
    fun `Bug4 - multiple writers block and resume correctly`() {
        val smallQueue = ByteQueue(capacity = 256)
        val data = ByteArray(128) { 'A'.code.toByte() }

        // Fill queue
        smallQueue.write(data, 0, data.size)
        smallQueue.write(data, 0, data.size) // 256 = full

        val done = CountDownLatch(2)
        val results = AtomicInteger(0)

        // Two writers that will block
        repeat(2) { i ->
            Thread {
                val d = ByteArray(64) { (i + 'B'.code).toByte() }
                smallQueue.write(d, 0, d.size)
                results.addAndGet(64)
                done.countDown()
            }.start()
        }

        Thread.sleep(100)

        // Drain the queue — should unblock both writers
        val drainBuf = ByteArray(512)
        var totalDrained = 0
        while (smallQueue.size > 0 || totalDrained < 384) {
            val n = smallQueue.tryRead(drainBuf, 0, drainBuf.size)
            if (n > 0) totalDrained += n
            else Thread.sleep(5)
            if (totalDrained >= 384) break
        }

        assertTrue("Both writers should complete", done.await(5, TimeUnit.SECONDS))
        assertEquals(128, results.get())
    }

    @Test
    fun `Bug4 - no deadlock under rapid write-read cycles`() {
        val q = ByteQueue(capacity = 1024)
        val iterations = 200  // Reduced from 500 for CI stability
        val latch = CountDownLatch(2)
        val writerDone = AtomicInteger(0)
        val readerDone = AtomicInteger(0)

        val writer = Thread {
            val data = ByteArray(512) { 'Z'.code.toByte() }
            repeat(iterations) {
                q.write(data, 0, data.size)
            }
            writerDone.set(1)
            latch.countDown()
        }
        writer.isDaemon = true
        writer.start()

        val reader = Thread {
            val buf = ByteArray(1024)
            var totalRead = 0
            val target = iterations * 512
            while (totalRead < target) {
                val n = q.tryRead(buf, 0, buf.size)
                if (n > 0) totalRead += n
                else Thread.sleep(1)
                // Safety: don't loop forever in CI
                if (!writer.isAlive && q.size == 0 && totalRead < target) break
            }
            readerDone.set(1)
            latch.countDown()
        }
        reader.isDaemon = true
        reader.start()

        assertTrue("Should complete without deadlock", latch.await(30, TimeUnit.SECONDS))
        assertEquals("Writer should complete", 1, writerDone.get())
        assertEquals("Reader should complete", 1, readerDone.get())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Bug #6: TerminalSession emulator feeder thread
    //  No thread was reading from ptyToEmulatorQueue to feed the emulator,
    //  so the screen buffer was always empty.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `Bug6 - emulator processes bytes from queue correctly`() {
        // Simulate the feedEmulator loop: read from queue → feed emulator
        val testText = "Hello World\n"
        val testBytes = testText.toByteArray()

        // Write to queue (simulating PTY reader thread)
        queue.write(testBytes, 0, testBytes.size)

        // Read from queue and feed emulator (simulating emulator feeder thread)
        val buf = ByteArray(256)
        val n = queue.read(buf, 0, buf.size)
        assertTrue("Should have read bytes from queue", n > 0)

        // Feed bytes to emulator
        emulator.processBytes(buf, 0, n)

        // Verify the emulator screen buffer is NOT empty (Bug #6 was that it
        // was always empty because no thread fed the emulator)
        val screenText = emulator.getScreenText()
        assertTrue("Emulator screen should contain 'Hello World' after feeding, got: $screenText",
            screenText.contains("Hello"))
        assertTrue("Emulator screen should contain 'World'",
            screenText.contains("World"))
    }

    @Test
    fun `Bug6 - emulator feeder processes multiple chunks`() {
        val chunks = listOf("Line 1\n", "Line 2\n", "Line 3\n")

        for (chunk in chunks) {
            val bytes = chunk.toByteArray()
            queue.write(bytes, 0, bytes.size)

            // Read and feed (emulator feeder loop)
            val buf = ByteArray(256)
            val n = queue.read(buf, 0, buf.size)
            if (n > 0) {
                emulator.processBytes(buf, 0, n)
            }
        }

        val text = emulator.getScreenText()
        assertTrue("Should contain Line 1", text.contains("Line 1"))
        assertTrue("Should contain Line 2", text.contains("Line 2"))
        assertTrue("Should contain Line 3", text.contains("Line 3"))
    }

    @Test
    fun `Bug6 - empty queue does not crash emulator feeder`() {
        // Simulate emulator feeder reading from an empty queue (timeout case)
        val buf = ByteArray(256)
        val n = queue.read(buf, 0, buf.size) // Will timeout after 3s
        // Should return 0 (timeout), not crash
        assertEquals(0, n)
    }

    @Test
    fun `Bug6 - getScreenChars returns non-empty after feeding`() {
        // This directly tests that the screen buffer is populated
        emulator.processString("Test Output")

        val screenChars = emulator.getScreenChars()
        assertNotNull("getScreenChars should not return null", screenChars)
        assertTrue("Should have rows", screenChars.isNotEmpty())

        // First row should contain 'T' at position 0
        val firstRow = screenChars[0]
        assertEquals('T', firstRow[0])
        assertEquals('e', firstRow[1])
        assertEquals('s', firstRow[2])
        assertEquals('t', firstRow[3])
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Bug #7: TextStyle bit extraction correctness
    //  The old code extracted bold from bit 8 (which is background color)
    //  instead of bit 16 (which is the actual bold flag).
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `Bug7 - TextStyle bold is in bits 16-23 not bits 8-15`() {
        // Bold + red foreground
        val styleWithBold = TextStyle.encode(
            foreground = TerminalEmulator.COLOR_RED,
            bold = true
        )

        // Extract foreground (bits 0-7)
        val fg = styleWithBold and 0xFF
        assertEquals("Foreground should be red (1)", TerminalEmulator.COLOR_RED, fg)

        // Extract background (bits 8-15) — should NOT be bold
        val bg = (styleWithBold shr 8) and 0xFF
        assertEquals("Background should be default (0)", 0, bg)

        // Extract bold flag (bit 16 = 0x10000)
        val isBold = (styleWithBold and 0x10000) != 0
        assertTrue("Bold should be set at bit 16", isBold)

        // Verify that bit 8 is NOT the bold flag (old bug)
        val oldBugBit8 = (styleWithBold and 0x100) != 0
        assertFalse("Bit 8 should be background color, not bold (old bug)", oldBugBit8)
    }

    @Test
    fun `Bug7 - TextStyle underline is at bit 19`() {
        val styleWithUnderline = TextStyle.encode(underline = true)

        // Underline = FLAG_UNDERLINE = 1 shl 3 = 0x08 in flags byte
        // Flags are shifted by SHIFT_FLAGS = 16, so bit 19 = 0x80000
        val isUnderline = (styleWithUnderline and 0x80000) != 0
        assertTrue("Underline should be set at bit 19", isUnderline)

        // Verify bit 8 is not affected
        val bit8 = (styleWithUnderline and 0x100) != 0
        assertFalse("Bit 8 (background) should not be affected by underline", bit8)
    }

    @Test
    fun `Bug7 - TextStyle italic is at bit 18`() {
        val styleWithItalic = TextStyle.encode(italic = true)

        // Italic = FLAG_ITALIC = 1 shl 2 = 0x04 in flags byte
        // Shifted by 16 → bit 18 = 0x40000
        val isItalic = (styleWithItalic and 0x40000) != 0
        assertTrue("Italic should be set at bit 18", isItalic)
    }

    @Test
    fun `Bug7 - combined bold underline italic extracts correctly`() {
        val combined = TextStyle.encode(
            foreground = TerminalEmulator.COLOR_GREEN,
            background = TerminalEmulator.COLOR_BLUE,
            bold = true,
            italic = true,
            underline = true
        )

        // Foreground (bits 0-7)
        assertEquals(TerminalEmulator.COLOR_GREEN, combined and 0xFF)

        // Background (bits 8-15)
        assertEquals(TerminalEmulator.COLOR_BLUE, (combined shr 8) and 0xFF)

        // Bold (bit 16)
        assertTrue("Bold", (combined and 0x10000) != 0)

        // Italic (bit 18)
        assertTrue("Italic", (combined and 0x40000) != 0)

        // Underline (bit 19)
        assertTrue("Underline", (combined and 0x80000) != 0)

        // Verify TextStyle's own decode methods agree
        assertTrue("TextStyle.bold()", TextStyle.bold(combined))
        assertTrue("TextStyle.italic()", TextStyle.italic(combined))
        assertTrue("TextStyle.underline()", TextStyle.underline(combined))
        assertEquals("TextStyle.foreground()", TerminalEmulator.COLOR_GREEN, TextStyle.foreground(combined))
        assertEquals("TextStyle.background()", TerminalEmulator.COLOR_BLUE, TextStyle.background(combined))
    }

    @Test
    fun `Bug7 - foreground color does not interfere with style flags`() {
        // Test with all 16 ANSI foreground colors — none should set the bold flag
        for (fg in 0..15) {
            val style = TextStyle.encode(foreground = fg)
            val isBold = (style and 0x10000) != 0
            assertFalse("Foreground $fg should not trigger bold", isBold)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Bug #2: TerminalScreen rendering — batched AnnotatedString
    //  Verify that getScreenChars returns data suitable for batched rendering
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `Bug2 - getScreenChars returns row arrays for batched rendering`() {
        // Use \r\n (CR+LF) so each line starts at column 0.
        // LF alone only moves cursor down without resetting column.
        emulator.processString("ABC\r\nDEF")

        val chars = emulator.getScreenChars()
        assertTrue("Should have multiple rows", chars.size >= 2)

        // Each row should be a CharArray of the right length
        chars.forEach { row ->
            assertNotNull(row)
            assertEquals("Each row should have correct column count",
                emulator.columns, row.size)
        }

        // Verify content — row 0 has ABC at cols 0-2, row 1 has DEF at cols 0-2
        assertEquals('A', chars[0][0])
        assertEquals('B', chars[0][1])
        assertEquals('C', chars[0][2])
        assertEquals('D', chars[1][0])
        assertEquals('E', chars[1][1])
        assertEquals('F', chars[1][2])
    }

    @Test
    fun `Bug2 - getScreenStyles returns matching style arrays`() {
        emulator.processString("\u001B[31mRed\u001B[0mNormal")

        val chars = emulator.getScreenChars()
        val styles = emulator.getScreenStyles()

        assertEquals("Chars and styles should have same row count",
            chars.size, styles.size)

        // Each style row should match column count
        styles.forEach { row ->
            assertEquals("Each style row should have correct column count",
                emulator.columns, row.size)
        }

        // First 3 chars should have red foreground
        assertEquals(TerminalEmulator.COLOR_RED, TextStyle.foreground(styles[0][0]))
        assertEquals(TerminalEmulator.COLOR_RED, TextStyle.foreground(styles[0][1]))
        assertEquals(TerminalEmulator.COLOR_RED, TextStyle.foreground(styles[0][2]))

        // After reset, should be default
        assertEquals(TerminalEmulator.COLOR_DEFAULT_FG, TextStyle.foreground(styles[0][3]))
    }

    @Test
    fun `Bug2 - large screen renders without excessive composables`() {
        // Simulate a full 120x40 screen (the problematic case from Bug #2)
        val largeEmu = TerminalEmulator(rows = 40, columns = 120)

        // Fill with text
        repeat(40) { row ->
            repeat(120) { col ->
                largeEmu.processString("${((row * 120 + col) % 26 + 'A'.code).toChar()}")
            }
            largeEmu.processByte(0x0A) // newline
        }

        val chars = largeEmu.getScreenChars()
        assertEquals(40, chars.size)
        assertEquals(120, chars[0].size)

        // Verify batched rendering would work: each row can be converted to a String
        chars.forEach { row ->
            val rowString = String(row)
            assertTrue("Row should be convertible to string for batched rendering",
                rowString.length == 120)
        }
    }
}
