package com.interndra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.interndra.ui.screens.MainScreen
import com.interndra.ui.theme.InterndraTheme
import com.interndra.ui.viewmodel.HybridAgentViewModel
import com.interndra.ui.viewmodel.HybridAgentViewModelFactory

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: HybridAgentViewModel

    // ── Runtime permission launcher ───────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Non-blocking: app works in degraded mode if permissions are denied
        results.forEach { (perm, granted) ->
            if (!granted) android.util.Log.w("Permissions", "Denied: $perm")
        }
        viewModel.refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashLogger()
        installSplashScreen()
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            HybridAgentViewModelFactory(application)
        )[HybridAgentViewModel::class.java]

        requestRequiredPermissions()

        setContent {
            InterndraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    /**
     * Writes any uncaught exception's full stack trace to a file in the
     * app's external files dir, then re-throws so the crash dialog still
     * shows normally. Lets you read the EXACT crash via Termux:
     *   cat /sdcard/Android/data/com.interndra/files/crash_log.txt
     * (If Termux can't read it: run `termux-setup-storage`, then enable
     *  "All files access" for Termux in Android Settings → Apps.)
     */
    private fun setupCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(getExternalFilesDir(null), "crash_log.txt")
                logFile.appendText(
                    "\n========== CRASH ${java.util.Date()} ==========\n" +
                    android.util.Log.getStackTraceString(throwable) + "\n"
                )
            } catch (_: Exception) { /* ignore — diagnostics must never crash harder */ }
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
        // Refresh accessibility + local model state when user returns from Settings
        viewModel.refreshStatus()
    }
}
