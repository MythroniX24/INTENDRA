/*
 * JniTermux.kt — JNI bridge to native PTY subprocess functions.
 *
 * Maps to the C functions in app/src/main/jni/termux.c.
 * Provides low-level access to forkpty(), ioctl(TIOCSWINSZ), waitpid().
 *
 * ## Usage
 * ```kotlin
 * val pid = IntArray(1)
 * val ptmFd = JniTermux.createSubprocess(
 *     cmd = "/data/local/tmp/intendra/termux/usr/bin/bash",
 *     cwd = "/data/local/tmp/intendra/termux/home",
 *     args = arrayOf("bash", "--login"),
 *     envVars = arrayOf("PATH=...", "HOME=...", "LD_PRELOAD=..."),
 *     processIdArray = pid,
 *     rows = 40, columns = 120,
 *     cellWidth = 8, cellHeight = 16
 * )
 * // Read/write from ptmFd (FileDescriptor)
 * // Later: JniTermux.setPtyWindowSize(ptmFd, 60, 180, 8, 16)
 * // On exit: JniTermux.waitFor(pid[0])
 * // Cleanup: JniTermux.close(ptmFd)
 * ```
 */
@file:JvmName("JniTermux")

package com.interndra.jni

import android.util.Log

/**
 * JNI bridge to native PTY subprocess management.
 *
 * These functions call directly into the C implementation in termux.c
 * via the NDK-built libtermux.so shared library.
 */
object JniTermux {

    private const val TAG = "JniTermux"

    /** Whether the native library was loaded successfully. */
    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("termux")
            isLoaded = true
            Log.i(TAG, "libtermux.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            Log.w(TAG, "libtermux.so not available: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NATIVE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Create a subprocess with a pseudo-terminal (PTY).
     *
     * @param cmd            Shell executable path (e.g., /path/to/bash)
     * @param cwd            Working directory for the child process
     * @param args           Array of command arguments (first should be command name)
     * @param envVars        Array of "KEY=VALUE" environment strings
     * @param processIdArray int[1] output — filled with child PID
     * @param rows           Terminal height in rows
     * @param columns        Terminal width in columns
     * @param cellWidth      Cell width in pixels
     * @param cellHeight     Cell height in pixels
     * @return PTY master file descriptor (positive int), or -1 on failure
     * @throws RuntimeException if the native call fails
     */
    @JvmStatic
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processIdArray: IntArray,
        rows: Int,
        columns: Int,
        cellWidth: Int,
        cellHeight: Int
    ): Int

    /**
     * Update the terminal window size.
     * Sends TIOCSWINSZ ioctl to the PTY master.
     */
    @JvmStatic
    external fun setPtyWindowSize(
        fd: Int,
        rows: Int,
        cols: Int,
        cellWidth: Int,
        cellHeight: Int
    ): Unit

    /**
     * Enable UTF-8 mode (IUTF8 flag) on the PTY.
     */
    @JvmStatic
    external fun setPtyUTF8Mode(fd: Int): Unit

    /**
     * Block until the child process exits.
     * @return exit code (positive), or negative signal number
     */
    @JvmStatic
    external fun waitFor(pid: Int): Int

    /**
     * Close a file descriptor.
     */
    @JvmStatic
    external fun close(fd: Int): Unit

    // ══════════════════════════════════════════════════════════════════════
    //  SAFE WRAPPERS (graceful fallback when native lib not loaded)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Safe version of [createSubprocess] — returns null on failure instead of
     * throwing, and gracefully falls back if native lib isn't loaded.
     */
    fun safeCreateSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        rows: Int = 40,
        columns: Int = 120,
        cellWidth: Int = 8,
        cellHeight: Int = 16
    ): PtyHandle? {
        if (!isLoaded) {
            Log.w(TAG, "Native lib not loaded, cannot create PTY")
            return null
        }
        return try {
            val pid = IntArray(1)
            val ptm = createSubprocess(cmd, cwd, args, envVars, pid, rows, columns, cellWidth, cellHeight)
            if (ptm < 0) {
                Log.e(TAG, "createSubprocess returned negative fd: $ptm")
                null
            } else {
                PtyHandle(ptm = ptm, pid = pid[0])
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSubprocess failed: ${e.message}", e)
            null
        }
    }

    /**
     * Safe wrapper for [setPtyWindowSize].
     */
    fun safeSetPtyWindowSize(fd: Int, rows: Int, cols: Int) {
        if (!isLoaded) return
        try {
            setPtyWindowSize(fd, rows, cols, 8, 16)
        } catch (e: Exception) {
            Log.w(TAG, "setPtyWindowSize failed: ${e.message}")
        }
    }

    /**
     * Safe wrapper for [waitFor].
     */
    fun safeWaitFor(pid: Int): Int {
        if (!isLoaded) return -1
        return try {
            waitFor(pid)
        } catch (e: Exception) {
            Log.w(TAG, "waitFor failed: ${e.message}")
            -1
        }
    }

    /**
     * Safe wrapper for [close].
     */
    fun safeClose(fd: Int) {
        if (!isLoaded) return
        try {
            close(fd)
        } catch (e: Exception) {
            Log.w(TAG, "close failed: ${e.message}")
        }
    }

    /**
     * Handle to an active PTY subprocess.
     */
    data class PtyHandle(
        /** PTY master file descriptor (used for read/write). */
        val ptm: Int,
        /** Child process ID. */
        val pid: Int
    )
}
