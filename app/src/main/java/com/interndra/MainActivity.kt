package com.interndra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.interndra.ui.screens.MainScreen
import com.interndra.ui.theme.InterndraTheme
import com.interndra.ui.theme.TerminalRed
import com.interndra.ui.theme.TerminalWhite
import com.interndra.ui.viewmodel.HybridAgentViewModel
import com.interndra.ui.viewmodel.HybridAgentViewModelFactory
import java.io.File

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private var viewModel: HybridAgentViewModel? = null
    private var vmInitError: String? = null

    // ── Runtime permission launcher ───────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            if (!granted) Log.w("Permissions", "Denied: $perm")
        }
        viewModel?.refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashLogger()
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // ── Safe ViewModel creation ───────────────────────────────────────
        // Catches any crash during ViewModel construction or init and shows
        // a graceful error screen instead of crashing the app.
        try {
            viewModel = ViewModelProvider(
                this,
                HybridAgentViewModelFactory(application)
            )[HybridAgentViewModel::class.java]
        } catch (e: Exception) {
            vmInitError = "${e::class.simpleName}: ${e.message}"
            Log.e(TAG, "ViewModel init failed: $vmInitError", e)
            // Write to crash log since the global handler may not capture this
            try {
                val logFile = File(filesDir, "crash_log.txt")
                logFile.appendText(
                    "\n========== CRASH (ViewModel init) ${java.util.Date()} ==========\n" +
                    Log.getStackTraceString(e) + "\n"
                )
            } catch (_: Exception) {}
        }

        requestRequiredPermissions()

        setContent {
            InterndraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    val vm = viewModel
                    if (vm != null) {
                        MainScreen(vm)
                    } else {
                        // ── Graceful error fallback ──────────────────────
                        // Shown when ViewModel construction throws — user
                        // can still see the error message and close the app.
                        ErrorFallbackScreen(vmInitError ?: "Unknown initialization error")
                    }
                }
            }
        }
    }

    @Composable
    private fun ErrorFallbackScreen(error: String) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "⚠️",
                    fontSize = 48.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "INTERNDRA couldn't start",
                    color = TerminalWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    color = TerminalRed,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Please check the crash log and report the issue.\n" +
                    "File: /data/data/com.interndra/files/crash_log.txt",
                    color = TerminalWhite.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
        }
    }

    /**
     * Sets up a crash handler that writes stack traces to BOTH internal and
     * external storage, so the crash log is always findable even when external
     * storage isn't mounted.
     */
    private fun setupCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            InterndraApplication.writeCrashLog(
                throwable,
                filesDir,                           // internal: always available
                getExternalFilesDir(null)           // external: may be null
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun requestRequiredPermissions() {
        val needed = buildList {
            // Voice input
            if (!has(Manifest.permission.RECORD_AUDIO))
                add(Manifest.permission.RECORD_AUDIO)

            // Contacts + calling + SMS (user-initiated only)
            if (!has(Manifest.permission.READ_CONTACTS))
                add(Manifest.permission.READ_CONTACTS)
            if (!has(Manifest.permission.CALL_PHONE))
                add(Manifest.permission.CALL_PHONE)
            if (!has(Manifest.permission.SEND_SMS))
                add(Manifest.permission.SEND_SMS)

            // Storage: Android 13+ uses READ_MEDIA_*, older uses READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!has(Manifest.permission.READ_MEDIA_IMAGES)) add(Manifest.permission.READ_MEDIA_IMAGES)
                if (!has(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                if (!has(Manifest.permission.READ_EXTERNAL_STORAGE))
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        viewModel?.refreshStatus()
    }
}
