/*
 * ByteQueue.kt — Thread-safe circular byte buffer for PTY I/O.
 *
 * A synchronized producer-consumer queue designed for terminal I/O:
 * - One thread writes bytes from the PTY master fd (input reader)
 * - One thread reads bytes to feed the TerminalEmulator (main thread)
 *
 * Based on the concept from Termux's ByteQueue.java.
 */

package com.interndra.terminal

import android.util.Log

/**
 * Thread-safe circular byte buffer.
 *
 * Supports blocking read and write operations with timeouts.
 * Multiple writers can call [write], a single reader calls [read].
 */
class ByteQueue(
    /** Maximum capacity in bytes. */
    private val capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        private const val TAG = "ByteQueue"

        /** Default buffer size: 64KB. */
        const val DEFAULT_CAPACITY = 64 * 1024

        /** Maximum write size per call (to avoid monopolizing the lock). */
        private const val MAX_WRITE_SIZE = 4096
    }

    private val buffer = ByteArray(capacity)
    private var head = 0  // read position
    private var tail = 0  // write position
    private var count = 0 // number of bytes currently in buffer

    // ── Metrics ─────────────────────────────────────────────────────────

    /** Total bytes written since creation. */
    var totalWritten: Long = 0
        private set
    /** Total bytes read since creation. */
    var totalRead: Long = 0
        private set
    /** Peak bytes stored concurrently. */
    var peakCount: Int = 0
        private set

    // ── Public API ──────────────────────────────────────────────────────

    /** Current number of bytes in the buffer. */
    val size: Int get() = synchronized(this) { count }

    /** Whether the buffer is empty. */
    val isEmpty: Boolean get() = synchronized(this) { count == 0 }

    /** Whether the buffer is full. */
    val isFull: Boolean get() = synchronized(this) { count == capacity }

    /** Available space for writing. */
    val availableForWrite: Int get() = synchronized(this) { capacity - count }

    /**
     * Write bytes to the queue. Blocks until space is available.
     *
     * @param src  Source byte array
     * @param offset  Offset in src to start reading from
     * @param len  Number of bytes to write
     * @return Actual number of bytes written (may be less if interrupted)
     */
    fun write(src: ByteArray, offset: Int = 0, len: Int = src.size): Int {
        var written = 0
        while (written < len) {
            val chunk: ByteArray
            val chunkLen: Int
            synchronized(this) {
                // Wait for space
                while (count == capacity) {
                    try {
                        (this as java.lang.Object).wait(100)
                    } catch (_: InterruptedException) {
                        return written
                    }
                    if (count < capacity) break
                }

                val remaining = len - written
                val batchSize = minOf(remaining, MAX_WRITE_SIZE, capacity - count)
                if (batchSize <= 0) continue

                // Copy data into circular buffer
                for (i in 0 until batchSize) {
                    val b = src[offset + written + i]
                    buffer[tail] = b
                    tail = (tail + 1) % capacity
                }
                count += batchSize
                written += batchSize
                totalWritten += batchSize
                if (count > peakCount) peakCount = count
            }
            // Notify reader outside lock
            synchronized(this) { (this as java.lang.Object).notifyAll() }
        }
        return written
    }

    /**
     * Read bytes from the queue. Blocks until data is available.
     *
     * @param dst  Destination byte array
     * @param offset  Offset in dst to start writing at
     * @param maxLen  Maximum number of bytes to read
     * @return Number of bytes actually read, or -1 if interrupted
     */
    fun read(dst: ByteArray, offset: Int = 0, maxLen: Int = dst.size): Int {
        synchronized(this) {
            // Wait for data
            while (count == 0) {
                try {
                    (this as java.lang.Object).wait(100)
                } catch (_: InterruptedException) {
                    return -1
                }
                if (count > 0) break
            }

            val batchSize = minOf(maxLen, count)
            for (i in 0 until batchSize) {
                dst[offset + i] = buffer[head]
                head = (head + 1) % capacity
            }
            count -= batchSize
            totalRead += batchSize

            // Notify writers that space is available
            (this as java.lang.Object).notifyAll()

            return batchSize
        }
    }

    /**
     * Non-blocking read. Returns 0 immediately if no data available.
     */
    fun tryRead(dst: ByteArray, offset: Int = 0, maxLen: Int = dst.size): Int {
        synchronized(this) {
            if (count == 0) return 0
            val batchSize = minOf(maxLen, count)
            for (i in 0 until batchSize) {
                dst[offset + i] = buffer[head]
                head = (head + 1) % capacity
            }
            count -= batchSize
            totalRead += batchSize
            return batchSize
        }
    }

    /**
     * Peek at the first byte without consuming it.
     * Returns -1 if empty.
     */
    fun peek(): Int {
        synchronized(this) {
            return if (count == 0) -1 else buffer[head].toInt() and 0xFF
        }
    }

    /**
     * Skip (discard) up to [n] bytes from the queue.
     * @return Actual number of bytes skipped
     */
    fun skip(n: Int): Int {
        synchronized(this) {
            val toSkip = minOf(n, count)
            head = (head + toSkip) % capacity
            count -= toSkip
            return toSkip
        }
    }

    /** Clear all data from the queue. */
    fun clear() {
        synchronized(this) {
            head = 0
            tail = 0
            count = 0
        }
    }

    /** Reset metrics counters. */
    fun resetMetrics() {
        totalWritten = 0
        totalRead = 0
        peakCount = 0
    }
}
