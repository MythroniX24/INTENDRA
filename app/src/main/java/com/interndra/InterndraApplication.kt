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
        private const val CRASH_FLAG_FILENAME = ".last_crashed"

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
                } catch (_: Exception) {}
            }
        }

        /**
         * Check if the app crashed on the previous launch.
         */
        @JvmStatic
        fun didCrashLastLaunch(app: Application): String? {
            val flagFile = File(app.filesDir, CRASH_FLAG_FILENAME)
            if (!flagFile.exists()) return null
            val info = try { flagFile.readText().trim().ifBlank { null } } catch (_: Exception) { null }
            flagFile.delete()
            return info
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "INTERNDRA starting — ${BuildConfig.VERSION_NAME}")

        // ── Application-level crash handler ──────────────────────────────
        // Replaces the default Android crash dialog with our own handler.
        // CRITICAL: We do NOT call defaultHandler - that shows the system
        // "App has stopped" dialog. Instead we write the crash info + set
        // a flag file. On next launch, MainActivity reads the flag and
        // shows a friendly error screen with the crash details.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                val info = "${throwable::class.simpleName}: ${throwable.message}\n\n$trace"
                writeCrashLog(
                    throwable,
                    filesDir,
                    getExternalFilesDir(null)
                )
                // Write crash flag so next launch shows error screen
                try {
                    File(filesDir, CRASH_FLAG_FILENAME).writeText(info.take(2000))
                } catch (_: Exception) {}
                Log.e(TAG, "💥 App crashed: ${throwable.message}")
            } catch (_: Exception) {}
            // Terminate the process without showing the system crash dialog
            android.os.Process.killProcess(android.os.Process.myPid())
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
