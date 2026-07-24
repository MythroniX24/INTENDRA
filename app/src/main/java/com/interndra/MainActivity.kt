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
import com.interndra.ui.theme.*
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

    private var crashRecoveryInfo: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashLogger()
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // ── Check if app crashed on previous launch ─────────────────────
        // If yes, show the crash info to the user with recovery options.
        crashRecoveryInfo = InterndraApplication.didCrashLastLaunch(application)

        // ── Crash Recovery Screen ──────────────────────────────────────────
    @Composable
    fun CrashRecoveryScreen(crashInfo: String) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "💥",
                    fontSize = 56.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "App crashed on previous launch",
                    color = TerminalRed,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = TerminalRed.copy(0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        crashInfo.take(500),
                        color = TerminalRed.copy(0.8f),
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 15,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "To recover: Clear app data or reinstall.",
                    color = TerminalWhite.copy(0.5f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        // Clear crash flag and restart
                        InterndraApplication.didCrashLastLaunch(application)
                        recreate()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Try Again", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

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
            InterndraTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    // ── Show crash recovery FIRST if app crashed before ─
                    val crashInfo = crashRecoveryInfo
                    if (crashInfo != null) {
                        CrashRecoveryScreen(crashInfo)
                    } else {
                        val vm = viewModel
                        if (vm != null) {
                            MainScreen(vm)
                        } else {
                            ErrorFallbackScreen(vmInitError ?: "Unknown initialization error")
                        }
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
        // Crash logging is handled at Application level in InterndraApplication.
        // No need to override here — it would conflict with the Application handler.
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
