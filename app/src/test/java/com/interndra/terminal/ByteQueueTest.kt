package com.interndra.terminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ByteQueueTest — comprehensive tests for the thread-safe circular byte buffer
 * used for PTY → emulator data flow.
 */
class ByteQueueTest {

    private lateinit var queue: ByteQueue

    @Before
    fun setUp() {
        queue = ByteQueue()
    }

    // ── Basic Write/Read ──────────────────────────────────────────────

    @Test
    fun `write and read roundtrip`() {
        val data = "Hello, World!".toByteArray()
        queue.write(data, 0, data.size)
        val buf = ByteArray(100)
        val n = queue.tryRead(buf, 0, buf.size)
        assertEquals(data.size, n)
        assertArrayEquals(data, buf.copyOf(n))
    }

    @Test
    fun `read returns 0 when empty`() {
        val buf = ByteArray(100)
        val n = queue.tryRead(buf, 0, buf.size)
        assertEquals(0, n)
    }

    @Test
    fun `multiple writes accumulate correctly`() {
        queue.write(byteArrayOf(1, 2, 3), 0, 3)
        queue.write(byteArrayOf(4, 5, 6), 0, 3)
        val buf = ByteArray(100)
        // Read all 6 bytes
        val n1 = queue.tryRead(buf, 0, 100)
        assertEquals(6, n1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buf.copyOf(6))
    }

    @Test
    fun `read limited by buf size`() {
        val data = "ABCDEFGHIJ".toByteArray() // 10 bytes
        queue.write(data, 0, data.size)
        val buf = ByteArray(5)
        val n = queue.tryRead(buf, 0, 5)
        assertEquals(5, n)
        assertArrayEquals("ABCDE".toByteArray(), buf.copyOf(5))
    }

    @Test
    fun `read with offset`() {
        val data = "Test".toByteArray()
        queue.write(data, 0, data.size)
        val buf = ByteArray(20)
        val n = queue.tryRead(buf, 5, data.size)
        assertEquals(4, n)
        assertArrayEquals("Test".toByteArray(), buf.copyOfRange(5, 9))
    }

    @Test
    fun `write with offset and length`() {
        val src = "XYHelloWorld".toByteArray()
        queue.write(src, 2, 5) // "Hello"
        val buf = ByteArray(100)
        val n = queue.tryRead(buf, 0, 100)
        assertEquals(5, n)
        assertEquals("Hello", String(buf, 0, n))
    }

    // ── Edge Cases ────────────────────────────────────────────────────

    @Test
    fun `write empty data does nothing`() {
        queue.write(ByteArray(0), 0, 0)
        val buf = ByteArray(100)
        assertEquals(0, queue.tryRead(buf, 0, 100))
    }

    @Test
    fun `write zero length does nothing`() {
        val data = "data".toByteArray()
        queue.write(data, 0, 0)
        val buf = ByteArray(100)
        assertEquals(0, queue.tryRead(buf, 0, 100))
    }

    @Test
    fun `read with zero length returns 0`() {
        val data = "data".toByteArray()
        queue.write(data, 0, data.size)
        val buf = ByteArray(100)
        assertEquals(0, queue.tryRead(buf, 0, 0))
    }

    @Test
    fun `duplicate writes do not corrupt`() {
        val data = "ABC".toByteArray()
        queue.write(data, 0, data.size)
        queue.write(data, 0, data.size)
        queue.write(data, 0, data.size)

        val buf = ByteArray(100)
        val n = queue.tryRead(buf, 0, 100)
        assertEquals(9, n)
        assertEquals("ABCABCABC", String(buf, 0, 9))
    }

    @Test
    fun `large write read cycle`() {
        val data = ByteArray(8192) { (it % 256).toByte() }
        queue.write(data, 0, data.size)
        val buf = ByteArray(8192)
        val n = queue.tryRead(buf, 0, buf.size)
        assertEquals(data.size, n)
        assertArrayEquals(data, buf.copyOf(n))
    }

    @Test
    fun `sequential partial reads drain correctly`() {
        val data = "0123456789".toByteArray()
        queue.write(data, 0, data.size)

        val buf = ByteArray(3)
        for (i in 0..2) {
            val n = queue.tryRead(buf, 0, 3)
            if (i < 3) assertTrue(n > 0)
        }
        // After 3 reads of 3 each = 9 bytes consumed, 1 left
        val remaining = queue.tryRead(buf, 0, 10)
        assertEquals(1, remaining)
    }

    // ── Thread Safety ─────────────────────────────────────────────────

    @Test
    fun `concurrent write read does not lose data`() {
        val iterations = 1000
        val latch = CountDownLatch(2)
        val readCount = AtomicInteger(0)
        val totalWritten = AtomicInteger(0)
        val testData = "Hello".toByteArray()

        val writer = Thread {
            repeat(iterations) {
                queue.write(testData, 0, testData.size)
                totalWritten.addAndGet(testData.size)
            }
            latch.countDown()
        }

        val reader = Thread {
            val buf = ByteArray(testData.size)
            var read = 0
            while (latch.count > 1 || read < totalWritten.get()) {
                val n = queue.tryRead(buf, 0, buf.size)
                read += n
                readCount.addAndGet(n)
                if (n == 0) Thread.sleep(1)
            }
            latch.countDown()
        }

        writer.start()
        reader.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(totalWritten.get(), readCount.get())
    }

    @Test
    fun `multiple concurrent writers`() {
        val numWriters = 4
        val writesPerWriter = 250
        val latch = CountDownLatch(numWriters)
        val data = "X".toByteArray()

        repeat(numWriters) {
            Thread {
                repeat(writesPerWriter) {
                    queue.write(data, 0, 1)
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))

        val buf = ByteArray(numWriters * writesPerWriter)
        val n = queue.tryRead(buf, 0, buf.size)
        assertEquals(numWriters * writesPerWriter, n)
    }

    @Test
    fun `writer wakes after reader consumes space`() {
        // Fill buffer close to capacity with small writes
        val data = ByteArray(1024) { 'X'.code.toByte() }
        repeat(8) { queue.write(data, 0, data.size) }

        val written = AtomicInteger(0)
        val done = CountDownLatch(2)

        Thread {
            queue.write(data, 0, data.size)
            written.incrementAndGet()
            done.countDown()
        }.start()

        Thread {
            val buf = ByteArray(4096)
            var total = 0
            while (total < 4096) {
                total += queue.tryRead(buf, 0, buf.size - total.coerceAtMost(buf.size))
                Thread.sleep(2)
            }
            done.countDown()
        }.start()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, written.get())
    }
}
