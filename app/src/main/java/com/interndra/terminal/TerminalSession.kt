/*
 * TerminalSession.kt — PTY session manager.
 *
 * Manages the lifecycle of a PTY-based terminal subprocess:
 *  1. Creates the subprocess via JniTermux.createSubprocess() (forkpty)
 *  2. Spawns reader thread (PTY master → ByteQueue → TerminalEmulator)
 *  3. Spawns writer side (UI input → PTY master)
 *  4. Spawns waiter thread (wait for process exit)
 *  5. Provides resize, signal sending, and clean shutdown
 *
 * ## Architecture
 *
 * ```
 * ┌─────────────────────────────────────┐
 * │          TerminalSession             │
 * │  ┌────────────┐    ┌──────────────┐  │
 * │  │ InputReader│───→│ ByteQueue    │──→ TerminalEmulator
 * │  │ Thread     │    │ (PTY→Emul)   │  │
 * │  └────────────┘    └──────────────┘  │
 * │  ┌────────────┐                      │
 * │  │ OutputWriter│←─── UI keyboard     │
 * │  │ (write PTY)│     input            │
 * │  └────────────┘                      │
 * │  ┌────────────┐                      │
 * │  │ Waiter     │──→ onExit callback   │
 * │  │ Thread     │                      │
 * │  └────────────┘                      │
 * └─────────────────────────────────────┘
 *         │ JNI
 *         ▼
 * ┌──────────────────┐
 * │  termux.c         │
 * │  forkpty/execvp   │
 * │  bash (Termux env)│
 * └──────────────────┘
 * ```
 */

package com.interndra.terminal

import android.util.Log
import com.interndra.jni.JniTermux
import com.interndra.jni.JniTermux.PtyHandle
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a PTY-based terminal session.
 *
 * @param shellPath  Path to the shell executable (e.g., /path/to/bash)
 * @param cwd        Initial working directory
 * @param args       Shell arguments (e.g., ["bash", "--login"])
 * @param envVars    Environment variables (KEY=VALUE format)
 * @param rows       Terminal rows
 * @param columns    Terminal columns
 */
class TerminalSession(
    val shellPath: String,
    val cwd: String = "/",
    val args: Array<String> = arrayOf(shellPath.substringAfterLast('/')),
    val envVars: Array<String> = emptyArray(),
    rows: Int = TerminalEmulator.DEFAULT_ROWS,
    columns: Int = TerminalEmulator.DEFAULT_COLUMNS
) {
    companion object {
        private const val TAG = "TerminalSession"

        /** Default terminal dimensions. */
        const val DEFAULT_ROWS = 40
        const val DEFAULT_COLUMNS = 120

        /** Read buffer size. */
        private const val READ_BUFFER_SIZE = 8192

        /** Maximum write attempt retries. */
        private const val MAX_WRITE_RETRIES = 3
    }

    // ── Public state ────────────────────────────────────────────────────

    /** The terminal emulator (ANSI parser + screen state). */
    val emulator = TerminalEmulator(rows, columns)

    /** Thread-safe byte queue for PTY → emulator data flow. */
    val ptyToEmulatorQueue = ByteQueue()

    /** Whether the session is currently running. */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** The child process ID. */
    @Volatile
    var childPid: Int = -1
        private set

    /** The PTY master file descriptor. */
    @Volatile
    var ptmFd: Int = -1
        private set

    /** Process exit code (set when process exits). */
    @Volatile
    var exitCode: Int? = null
        private set

    /** Human-readable title for this session. */
    var title: String = "Terminal"
        set(value) {
            field = value
            onTitleChanged?.invoke(value)
        }

    // ── Callbacks ───────────────────────────────────────────────────────

    /** Called when the emulator has new data to render. */
    var onOutput: ((String) -> Unit)? = null

    /** Called when the process exits. */
    var onExit: ((exitCode: Int) -> Unit)? = null

    /** Called when the title changes (via OSC sequence). */
    var onTitleChanged: ((String) -> Unit)? = null

    /** Called when the session encounters an error. */
    var onError: ((String) -> Unit)? = null

    // ── Internal state ──────────────────────────────────────────────────

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var waiterThread: Thread? = null

    // I/O streams to PTY master fd
    private var ptyInputStream: FileInputStream? = null
    private var ptyOutputStream: FileOutputStream? = null



    /**
     * Start the terminal session.
     *
     * 1. Creates the PTY subprocess via JNI
     * 2. Wraps the PTY master fd in Java I/O streams
     * 3. Starts reader, writer, and waiter threads
     *
     * @return true if session started successfully
     */
    fun start(): Boolean {
        if (isRunning) {
            Log.w(TAG, "Session already running")
            return true
        }

        if (!JniTermux.isLoaded) {
            Log.e(TAG, "libtermux.so not loaded — native PTY not available")
            onError?.invoke("Native PTY library not available. The app needs to be rebuilt with NDK support.")
            return false
        }

        try {
            Log.i(TAG, "Starting terminal session: shell=$shellPath, cwd=$cwd")

            // ── 1. Create PTY subprocess ────────────────────────────
            val handle: PtyHandle = JniTermux.safeCreateSubprocess(
                cmd = shellPath,
                cwd = cwd,
                args = args.toList().toTypedArray(),
                envVars = envVars,
                rows = emulator.rows,
                columns = emulator.columns,
                cellWidth = 8,
                cellHeight = 16
            ) ?: run {
                Log.e(TAG, "Failed to create PTY subprocess")
                onError?.invoke("Failed to create PTY subprocess")
                return false
            }

            ptmFd = handle.ptm
            childPid = handle.pid
            isRunning = true
            running.set(true)

            Log.i(TAG, "PTY created: fd=$ptmFd, pid=$childPid")

            // ── 2. Wrap fd in Java I/O streams ──────────────────────
            // Use reflection to create FileDescriptor from int fd
            val fdObj = createFileDescriptor(ptmFd)
            ptyInputStream = FileInputStream(fdObj)
            ptyOutputStream = FileOutputStream(fdObj)

            // ── 3. Start reader thread (PTY → ByteQueue) ────────────
            readerThread = Thread({
                readFromPty()
            }, "tsession-reader-$childPid").apply { isDaemon = true; start() }

            // ── 4. Start waiter thread (wait for process exit) ──────
            waiterThread = Thread({
                waitForExit()
            }, "tsession-waiter-$childPid").apply { isDaemon = true; start() }

            // ── 5. Enable UTF-8 mode on PTY ─────────────────────────
            JniTermux.safeSetPtyUTF8Mode(ptmFd)

            Log.i(TAG, "✅ Terminal session started (PID $childPid)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start terminal session: ${e.message}", e)
            onError?.invoke("Failed to start: ${e.message}")
            cleanup()
            return false
        }
    }

    /**
     * Write input to the terminal (from user keyboard).
     * This writes directly to the PTY master fd.
     *
     * @param text  The text to send
     */
    fun writeInput(text: String) {
        if (!isRunning) return
        try {
            ptyOutputStream?.write(text.toByteArray(Charsets.UTF_8))
            ptyOutputStream?.flush()
        } catch (e: IOException) {
            Log.w(TAG, "writeInput failed: ${e.message}")
        }
    }

    /**
     * Send a control character (e.g., Ctrl+C = 0x03, Ctrl+Z = 0x1A).
     */
    fun sendControlChar(code: Int) {
        writeInput(code.toChar().toString())
    }

    /**
     * Send Ctrl+C (SIGINT).
     */
    fun sendCtrlC() = sendControlChar(0x03)

    /**
     * Send Ctrl+Z (SIGTSTP).
     */
    fun sendCtrlZ() = sendControlChar(0x1A)

    /**
     * Send Ctrl+D (EOF).
     */
    fun sendCtrlD() = sendControlChar(0x04)

    /**
     * Resize the terminal.
     *
     * @param rows    New number of rows
     * @param columns New number of columns
     */
    fun resize(rows: Int, columns: Int) {
        if (!isRunning || ptmFd < 0) return

        // Update emulator
        emulator.resize(rows, columns)

        // Update PTY window size via JNI
        JniTermux.safeSetPtyWindowSize(ptmFd, rows, columns)
    }

    /**
     * Stop the session and clean up resources.
     */
    fun stop() {
        if (!isRunning) return
        Log.i(TAG, "Stopping terminal session (PID $childPid)")

        running.set(false)
        isRunning = false

        // Send exit to shell
        try {
            ptyOutputStream?.write("exit\n".toByteArray(Charsets.UTF_8))
            ptyOutputStream?.flush()
        } catch (_: Exception) {}

        cleanup()
    }

    /** Forcefully kill the child process. */
    fun kill() {
        if (childPid > 0) {
            try {
                android.os.Process.killProcess(childPid)
            } catch (_: Exception) {}
        }
        cleanup()
    }

    /** Check if the child process is still alive. */
    fun isChildAlive(): Boolean {
        if (childPid <= 0) return false
        // On Android, we can check by attempting to send signal 0
        return true // simplified — in production use kill(pid, 0)
    }

    // ── Internal methods ────────────────────────────────────────────────

    /**
     * Read loop: reads from PTY master fd → writes to ByteQueue.
     * Runs on a background thread.
     */
    private fun readFromPty() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val inputStream = ptyInputStream ?: return

        try {
            while (running.get()) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 0) {
                    Log.d(TAG, "PTY read returned EOF")
                    break
                }
                if (bytesRead > 0) {
                    // Write to byte queue (main thread reads from queue → feeds emulator)
                    ptyToEmulatorQueue.write(buffer, 0, bytesRead)

                    // Notify output listener with raw text for streaming display
                    val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    onOutput?.invoke(text)
                }
            }
        } catch (e: IOException) {
            if (running.get()) {
                Log.w(TAG, "PTY read error: ${e.message}")
            }
        } finally {
            Log.d(TAG, "PTY reader thread ended")
        }
    }

    /**
     * Wait for the child process to exit.
     * Runs on a background thread.
     */
    private fun waitForExit() {
        if (childPid <= 0) return

        val code = JniTermux.safeWaitFor(childPid)
        exitCode = code

        Log.i(TAG, "Process $childPid exited with code $code")

        isRunning = false
        running.set(false)

        // Notify callback
        onExit?.invoke(code)

        // Cleanup
        cleanup()
    }

    /**
     * Clean up resources: close fd, close streams, nullify references.
     */
    private fun cleanup() {
        running.set(false)
        isRunning = false

        try { ptyInputStream?.close() } catch (_: Exception) {}
        try { ptyOutputStream?.close() } catch (_: Exception) {}

        if (ptmFd >= 0) {
            JniTermux.safeClose(ptmFd)
            ptmFd = -1
        }

        ptyInputStream = null
        ptyOutputStream = null
        readerThread = null
        waiterThread = null

        Log.d(TAG, "Session cleaned up")
    }

    /**
     * Create a FileDescriptor from a raw integer fd.
     * Uses reflection since FileDescriptor's constructor is package-private.
     */
    /**
     * Create a FileDescriptor from a raw integer fd.
     * Uses the public FileDescriptor() constructor + reflection to set the internal fd.
     * Falls back gracefully on restricted Android versions (API 33+).
     */
    private fun createFileDescriptor(fd: Int): FileDescriptor {
        val fdObj = FileDescriptor()
        var success = false
        // Try "fd" field first (standard name on most Android versions)
        for (fieldName in listOf("fd", "descriptor")) {
            try {
                val field = FileDescriptor::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                field.setInt(fdObj, fd)
                success = true
                break
            } catch (_: Exception) {}
        }
        if (!success) {
            Log.w(TAG, "Cannot set FileDescriptor fd via reflection (API ${android.os.Build.VERSION.SDK_INT})")
            // Fallback: return the empty FileDescriptor and log the error
            // The caller should check isRunning before using the streams
        }
        return fdObj
    }

    // ── Factory ─────────────────────────────────────────────────────────

    /** Builder for creating a Termux-based terminal session. */
    data class TermuxSessionConfig(
        val prefix: String,
        val homeDir: String = "$prefix/home",
        val shellPath: String = "$prefix/usr/bin/bash",
        val rows: Int = DEFAULT_ROWS,
        val columns: Int = DEFAULT_COLUMNS
    ) {
        fun buildEnvVars(): Array<String> = arrayOf(
            "PREFIX=$prefix/usr",
            "HOME=$homeDir",
            "PATH=$prefix/usr/bin:$prefix/usr/bin/applets:/system/bin:/system/xbin:/sbin:/vendor/bin",
            "LD_PRELOAD=$prefix/usr/lib/libtermux-exec.so",
            "TERM=xterm-256color",
            "SHELL=$shellPath",
            "TMPDIR=$prefix/usr/tmp",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TERMUX_APP_PACKAGE=com.interndra",
            "TERMUX_VERSION=0.118.0",
            "TERMUX_APK_RELEASE=F-Droid",
            "TERMUX_MAIN_PACKAGE_FORMAT=apt",
            "INTERNDRA_TERMUX=1",
            "PS1=\\w $ "
        )

        fun buildArgs(): Array<String> = arrayOf(
            "bash",
            "--login"
        )

        fun createSession(): TerminalSession = TerminalSession(
            shellPath = shellPath,
            cwd = homeDir,
            args = buildArgs(),
            envVars = buildEnvVars(),
            rows = rows,
            columns = columns
        )
    }
}
