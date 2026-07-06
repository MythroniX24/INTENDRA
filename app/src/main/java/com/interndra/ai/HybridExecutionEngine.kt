package com.interndra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.interndra.agent.TerminalAgent
import com.interndra.data.local.AgentRepository
import com.interndra.data.model.*
import com.interndra.service.SmartShell
import com.interndra.service.TermuxBridge
import java.io.File

/**
 * HybridExecutionEngine — executes a parsed AiIntent step by step.
 *
 * FIXES (Phase 2/5/6/10):
 *  1. ANR FIX: shell commands now run via `shell.runAsync()` (suspend) so
 *     the main thread is never blocked by `Runtime.exec().waitFor()`.
 *  2. WORKFLOW SUPPORT: new command sub-formats for Phase 5/6 —
 *       - `sendtext:<pkg>:<text>`           (share sheet — existing)
 *       - `sendtext:<pkg>:<contact>:<text>`  (WhatsApp direct to contact via deep link)
 *       - `open:<pkg>` / `open_app:<pkg>`   (launch app — existing)
 *       - `openfile:<absolute-path>`         (open file via ACTION_VIEW + FileProvider)
 *       - `sharefile:<absolute-path>[:<mime>]` (share a file via ACTION_SEND)
 *       - `call:+<phone>`                    (ACTION_CALL — user-initiated)
 *       - `dial:+<phone>`                    (ACTION_DIAL — user confirms)
 *       - `sms:<phone>:<body>`               (open SMS app with body pre-filled)
 *  3. CRITICAL-STEP DETECTION: replaces the fragile `description.contains("!")`
 *     heuristic with a proper `cmd.critical: Boolean` flag (default true for
 *     the first command, false otherwise) plus the legacy "!" fallback.
 *  4. ACCESSIBILITY: returns a clear actionable error message with the
 *     Android Settings path so the user knows exactly what to enable.
 *  5. PACKAGE PRESENCE CHECK: verifies the target package is installed
 *     before invoking startActivity — clearer error than ActivityNotFoundException.
 *  6. FILE SAFETY: `openfile` / `sharefile` reject paths outside the
 *     app-private dirs or shared storage; uses FileProvider (no world-readable
 *     file permissions).
 */
class HybridExecutionEngine(
    private val context: Context,
    private val repo: AgentRepository,
    private val shell: SmartShell,
    private val safety: SafetyEngine,
    private val termuxBridge: TermuxBridge = TermuxBridge(context),
    private val terminalAgent: TerminalAgent? = null
) {
    companion object {
        private const val TAG = "HybridExecEngine"

        // File extensions that are safe to open/share via ACTION_VIEW.
        private val SAFE_FILE_EXTENSIONS = setOf(
            "pdf", "txt", "md", "json", "xml", "html", "htm", "csv",
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "mp3", "wav", "ogg", "m4a",
            "mp4", "mkv", "webm", "3gp",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "tar", "gz"
        )
    }

    suspend fun execute(
        session: String,
        userInput: String,
        intent: AiIntent,
        onStepResult: (ExecutionResult) -> Unit
    ) {
        // Re-validate all commands together — catches cross-command context the old
        // single-check isSafe() could miss.
        val safetyReports = safety.validateAll(intent.commands)

        intent.commands.forEachIndexed { index, cmd ->
            val report = safetyReports.getOrNull(index)

            // Hard block
            if (report?.result == SafetyEngine.ValidationResult.BLOCKED) {
                Log.w(TAG, "Step $index blocked: ${report.reason}")
                onStepResult(ExecutionResult(
                    stepIndex = index,
                    success   = false,
                    output    = "",
                    error     = "Blocked by SafetyEngine: ${report.reason}"
                ))
                return@forEachIndexed
            }

            // Execute based on command type
            val result: ExecutionResult = when (cmd.type) {
                CommandType.ADB_SHELL -> executeShell(index, cmd)

                CommandType.TERMUX -> executeInTermux(index, cmd)

                CommandType.ANDROID_INTENT -> executeIntent(index, cmd)

                CommandType.ACCESSIBILITY -> ExecutionResult(
                    stepIndex = index,
                    success   = false,
                    output    = "",
                    error     = "Accessibility actions require the INTERNDRA Accessibility " +
                                "Service to be enabled in Android Settings → Accessibility → " +
                                "INTERNDRA. UI automation is opt-in and must be turned on manually."
                )
            }

            onStepResult(result)

            // Stop chain on failure of a critical step.
            // A step is "critical" if cmd.critical is true OR its description
            // contains a "!" marker (legacy convention).
            val isCritical = cmd.critical || cmd.description.contains("!")
            if (!result.success && isCritical) {
                Log.w(TAG, "Critical step $index failed — aborting chain")
                return
            }
        }
    }

    private suspend fun executeShell(index: Int, cmd: ShellCommand): ExecutionResult =
        try {
            val output = shell.runAsync(cmd.command)
            ExecutionResult(stepIndex = index, success = true, output = output)
        } catch (e: Exception) {
            Log.e(TAG, "Shell error on step $index: ${e.message}")
            ExecutionResult(stepIndex = index, success = false, output = "", error = e.message ?: "Shell error")
        }

    /**
     * Execute a command in Termux's full Linux environment.
     * Uses TerminalAgent for persistent sessions if available.
     * Enables: pkg install, pip, npm, git, python, apt, etc.
     */
    private suspend fun executeInTermux(index: Int, cmd: ShellCommand): ExecutionResult =
        try {
            if (!termuxBridge.isTermuxInstalled()) {
                return ExecutionResult(
                    stepIndex = index,
                    success = false,
                    output = "",
                    error = "Termux is not installed. Install Termux from F-Droid or GitHub to run this command.\n" +
                            "Download: https://f-droid.org/packages/com.termux/"
                )
            }

            val agent = terminalAgent
            if (agent != null) {
                // Use TerminalAgent for session-aware execution with auto-recovery
                val sessionName = "exec_$index"
                val result = agent.executeWithRecovery(sessionName, cmd.command)
                if (result.isSuccess) {
                    val output = if (result.stdout.isNotBlank()) result.stdout else "(completed)"
                    val recoveryNote = if (result.wasRecovered)
                        "\n\n⚡ Auto-recovered: ${result.recoveryActions.firstOrNull() ?: "applied fix"}" else ""
                    ExecutionResult(stepIndex = index, success = true, output = output + recoveryNote)
                } else {
                    ExecutionResult(stepIndex = index, success = false,
                        output = result.stdout, error = result.stderr)
                }
            } else {
                // Direct Termux execution (no session management)
                val result = termuxBridge.executeShell(cmd.command)
                if (result.isSuccess) {
                    val output = if (result.stdout.isNotBlank()) result.stdout else "(completed)"
                    ExecutionResult(stepIndex = index, success = true, output = output)
                } else {
                    val errMsg = if (result.stderr.isNotBlank()) result.stderr else "Exit code: ${result.exitCode}"
                    ExecutionResult(stepIndex = index, success = false, output = result.stdout, error = errMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Termux error on step $index: ${e.message}")
            ExecutionResult(stepIndex = index, success = false, output = "", error = "Termux error: ${e.message}")
        }

    private fun executeIntent(index: Int, cmd: ShellCommand): ExecutionResult {
        val cmdStr = cmd.command.trim()
        return try {
            when {
                cmdStr.startsWith("sendtext:", ignoreCase = true) ->
                    handleSendText(index, cmdStr)

                cmdStr.startsWith("openfile:", ignoreCase = true) ->
                    handleOpenFile(index, cmdStr.removePrefix("openfile:").removePrefix("OPENFILE:").trim())

                cmdStr.startsWith("sharefile:", ignoreCase = true) ->
                    handleShareFile(index, cmdStr.removePrefix("sharefile:").removePrefix("SHAREFILE:").trim())

                cmdStr.startsWith("call:", ignoreCase = true) ->
                    handleCall(index, cmdStr.removePrefix("call:").removePrefix("CALL:").trim(), direct = true)

                cmdStr.startsWith("dial:", ignoreCase = true) ->
                    handleCall(index, cmdStr.removePrefix("dial:").removePrefix("DIAL:").trim(), direct = false)

                cmdStr.startsWith("sms:", ignoreCase = true) ->
                    handleSms(index, cmdStr.removePrefix("sms:").removePrefix("SMS:").trim())

                else -> handleLaunchApp(index, cmdStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Intent launch error on step $index: ${e.message}")
            ExecutionResult(index, false, "", e.message ?: "Intent error")
        }
    }

    /**
     * sendtext:<package>:<message>
     * sendtext:<package>:<contact>:<message>   (WhatsApp deep-link to a specific contact)
     *
     * Opens the target app's share sheet (or WhatsApp's contact picker) with
     * the message pre-filled. The user picks the contact and taps Send —
     * INTERNDRA never auto-sends to an auto-picked contact.
     */
    private fun handleSendText(index: Int, cmdStr: String): ExecutionResult {
        val rest = cmdStr.substringAfter(":")
        val parts = rest.split(":", limit = 3).map { it.trim() }
        val pkg   = parts.getOrNull(0)?.ifBlank { "com.whatsapp" } ?: "com.whatsapp"
        val text: String
        val contact: String?

        when (parts.size) {
            2 -> { contact = null; text = parts[1] }
            3 -> { contact = parts[1].ifBlank { null }; text = parts[2] }
            else -> return ExecutionResult(index, false, "", "sendtext format: sendtext:<package>[:<contact>]:<message>")
        }

        if (!isPackageInstalled(pkg)) {
            return ExecutionResult(index, false, "", "App not installed: $pkg")
        }

        // WhatsApp has a deep link `whatsapp://send?text=...&phone=...` that
        // pre-selects a contact (still requires the user to tap Send).
        if (pkg == "com.whatsapp" && contact != null) {
            val deepLink = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$contact&text=${Uri.encode(text)}")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(deepLink)
                ExecutionResult(
                    index, true,
                    "Opened WhatsApp with your message pre-filled for $contact — review and tap Send to deliver."
                )
            } catch (e: Exception) {
                // Fall back to plain share sheet if deep link fails
                sharePlainText(pkg, text)
            }
        }

        return sharePlainText(pkg, text)
    }

    private fun sharePlainText(pkg: String, text: String): ExecutionResult {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(sendIntent)
        return ExecutionResult(
            -1, true,
            "Opened $pkg with your message pre-filled — pick the contact and tap Send to finish."
        ).copy(stepIndex = 0) // stepIndex patched by caller; rely on caller's index
    }

    /**
     * openfile:<absolute-path>
     * Opens a file via ACTION_VIEW using a FileProvider URI.
     * Rejects paths outside app-private dirs or shared storage.
     */
    private fun handleOpenFile(index: Int, rawPath: String): ExecutionResult {
        val file = File(rawPath)
        if (!isPathSafe(file)) {
            return ExecutionResult(index, false, "", "Refusing to open file outside safe storage: $rawPath")
        }
        if (!file.exists() || !file.isFile) {
            return ExecutionResult(index, false, "", "File not found: $rawPath")
        }
        val ext = file.extension.lowercase()
        if (ext.isNotEmpty() && ext !in SAFE_FILE_EXTENSIONS) {
            return ExecutionResult(index, false, "", "Refusing to open file type '.$ext' for safety")
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeForExtension(ext))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(viewIntent)
            ExecutionResult(index, true, "Opened file: ${file.name}")
        } catch (e: Exception) {
            ExecutionResult(index, false, "", "No app available to open '.${ext}' files: ${e.message}")
        }
    }

    /**
     * sharefile:<absolute-path>[:<mime>]
     * Shares a file via ACTION_SEND with a chooser.
     */
    private fun handleShareFile(index: Int, rawSpec: String): ExecutionResult {
        val parts = rawSpec.split(":", limit = 2)
        val path = parts[0].trim()
        val mime = parts.getOrNull(1)?.trim()?.ifBlank { null }
        val file = File(path)
        if (!isPathSafe(file)) {
            return ExecutionResult(index, false, "", "Refusing to share file outside safe storage: $path")
        }
        if (!file.exists() || !file.isFile) {
            return ExecutionResult(index, false, "", "File not found: $path")
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime ?: mimeForExtension(file.extension.lowercase())
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            ExecutionResult(index, true, "Opened share sheet for: ${file.name}")
        } catch (e: Exception) {
            ExecutionResult(index, false, "", "No app available to share this file: ${e.message}")
        }
    }

    private fun handleCall(index: Int, phone: String, direct: Boolean): ExecutionResult {
        if (phone.isBlank()) return ExecutionResult(index, false, "", "No phone number provided")
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val callIntent = Intent(action, Uri.parse("tel:$phone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(callIntent)
            ExecutionResult(index, true, if (direct) "Calling $phone…" else "Opened dialer with $phone")
        } catch (e: Exception) {
            ExecutionResult(index, false, "", "Could not ${if (direct) "call" else "dial"} $phone: ${e.message}")
        }
    }

    private fun handleSms(index: Int, spec: String): ExecutionResult {
        val parts = spec.split(":", limit = 2)
        val phone = parts.getOrNull(0)?.trim().orEmpty()
        val body  = parts.getOrNull(1)?.trim().orEmpty()
        if (phone.isBlank()) return ExecutionResult(index, false, "", "No phone number provided")
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(smsIntent)
            ExecutionResult(index, true, "Opened SMS app with message pre-filled for $phone")
        } catch (e: Exception) {
            ExecutionResult(index, false, "", "Could not open SMS app: ${e.message}")
        }
    }

    /**
     * Legacy + new package-launch formats: "open:com.pkg", "open_app:com.pkg",
     * "com.pkg", "com.pkg/.Activity"
     */
    private fun handleLaunchApp(index: Int, cmdStr: String): ExecutionResult {
        val pkgName = cmdStr.removePrefix("open:").removePrefix("open_app:")
            .substringBefore("/").trim()
        if (pkgName.isBlank()) {
            return ExecutionResult(index, false, "", "No package name provided")
        }
        if (!isPackageInstalled(pkgName)) {
            return ExecutionResult(index, false, "", "App not installed: $pkgName")
        }
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(pkgName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ExecutionResult(index, false, "", "App has no launchable activity: $pkgName")
        context.startActivity(launchIntent)
        return ExecutionResult(index, true, "Launched $pkgName")
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * A file path is "safe" if it lives under:
     *  - app-private internal/external files dir
     *  - shared storage (Download, Documents, Pictures, DCIM, Music, Movies)
     *  - INTERNDRA's legacy /sdcard/INTERNDRA/ folder
     * Rejects /system, /proc, /dev, /data/data of OTHER apps, etc.
     */
    private fun isPathSafe(file: File): Boolean {
        val abs = file.absolutePath
        val allowedRoots = listOf(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath ?: "",
            context.getExternalCacheDir()?.absolutePath ?: "",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Music",
            "/storage/emulated/0/Movies",
            "/storage/emulated/0/INTERNDRA",
            "/sdcard/Download",
            "/sdcard/Documents",
            "/sdcard/Pictures",
            "/sdcard/DCIM",
            "/sdcard/Music",
            "/sdcard/Movies",
            "/sdcard/INTERNDRA"
        ).filter { it.isNotBlank() }
        return allowedRoots.any { abs.startsWith(it) }
    }

    private fun mimeForExtension(ext: String): String = when (ext) {
        "pdf"  -> "application/pdf"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "xml"  -> "application/xml"
        "html", "htm" -> "text/html"
        "csv"  -> "text/csv"
        "jpg", "jpeg" -> "image/jpeg"
        "png"  -> "image/png"
        "gif"  -> "image/gif"
        "webp" -> "image/webp"
        "bmp"  -> "image/bmp"
        "mp3"  -> "audio/mpeg"
        "wav"  -> "audio/wav"
        "ogg"  -> "audio/ogg"
        "m4a"  -> "audio/mp4"
        "mp4"  -> "video/mp4"
        "mkv"  -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp"  -> "video/3gpp"
        "doc"  -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls"  -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt"  -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip"  -> "application/zip"
        else   -> "*/*"
    }
}
