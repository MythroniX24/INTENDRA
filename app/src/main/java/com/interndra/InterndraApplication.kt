package com.interndra

import android.app.Application
import android.util.Log

/**
 * InterndraApplication — minimal Application class.
 *
 * UPGRADE: Added crash logging and strict-mode in debug builds.
 * No analytics or remote crash reporting — preserves local-first privacy.
 */
class InterndraApplication : Application() {

    companion object {
        private const val TAG = "InterndraApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "INTERNDRA starting — ${BuildConfig.VERSION_NAME}")

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
