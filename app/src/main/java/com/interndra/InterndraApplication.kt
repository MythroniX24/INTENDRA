package com.interndra

import android.app.Application
import android.util.Log
import java.io.File

/**
 * InterndraApplication — Application class with crash handling.
 *
 * Crash logging is set up at the Application level so that even crashes
 * that happen BEFORE MainActivity.onCreate() are captured. Logs are written
 * to TWO locations:
 *   1. Internal:  /data/data/com.interndra/files/crash_log.txt   (always available)
 *   2. External:  /sdcard/Android/data/com.interndra/files/crash_log.txt   (if available)
 *
 * UPGRADE: Added crash logging and strict-mode in debug builds.
 * No analytics or remote crash reporting — preserves local-first privacy.
 */
class InterndraApplication : Application() {

    companion object {
        private const val TAG = "InterndraApp"
        private const val CRASH_LOG_FILENAME = "crash_log.txt"

        /**
         * Write a crash log to every available location so the user can
         * always find it — even if external storage isn't mounted.
         */
        @JvmStatic
        fun writeCrashLog(throwable: Throwable, vararg logDirs: File?) {
            val trace = android.util.Log.getStackTraceString(throwable)
            val header = "\n========== CRASH ${java.util.Date()} ==========\n"
            val entry = "$header$trace\n"
            for (dir in logDirs) {
                if (dir == null) continue
                try {
                    val file = File(dir, CRASH_LOG_FILENAME)
                    file.appendText(entry)
                } catch (_: Exception) {
                    // Never crash harder while trying to log a crash
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "INTERNDRA starting — ${BuildConfig.VERSION_NAME}")

        // ── Application-level crash handler ──────────────────────────────
        // Catches crashes that happen BEFORE MainActivity.onCreate() runs.
        // Writes to internal storage (always available on API 26+) so the
        // crash log is always findable even if external storage is missing.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(
                    throwable,
                    filesDir,                           // internal: always available
                    getExternalFilesDir(null)           // external: may be null
                )
            } catch (_: Exception) { /* last resort — silent */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
