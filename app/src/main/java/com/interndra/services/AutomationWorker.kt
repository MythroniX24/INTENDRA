package com.interndra.services

import android.content.Context
import android.util.Log
import android.content.Intent
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.interndra.service.ShellExecutor

/**
 * AutomationWorker — executes scheduled shell commands via WorkManager.
 *
 * BUG FIX: original used runBlocking{} inside doWork() which can cause ANR when
 *   a shell command hangs. SmartShell.run() is synchronous and enforces its own
 *   timeout, so runBlocking is unnecessary and removed.
 *
 * SAFETY FIX: commands are re-validated by SafetyEngine before execution because
 *   the stored command could have been crafted maliciously or changed since scheduling.
 */
class AutomationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "AutomationWorker"
    }

    override fun doWork(): Result {
        val type    = inputData.getString("TYPE")    ?: return Result.failure()
        val command = inputData.getString("COMMAND") ?: return Result.failure()

        Log.d(TAG, "Scheduled task running: type=$type cmd=${com.interndra.security.SensitiveDataRedactor.redact(command).take(60)}")

        // WorkManager has no interactive confirmation UI. Use the same pure
        // preflight as immediate execution before any side effect.
        val rule = com.interndra.data.model.AutomationRule(
            id = inputData.getLong("RULE_ID", 0L),
            description = inputData.getString("DESCRIPTION").orEmpty(),
            commandType = type,
            command = command
        )
        val preflight = AutomationPreflight.validate(rule)
        if (!preflight.success) {
            Log.w(TAG, "Scheduled command refused: ${preflight.refusalSource}: ${preflight.error}")
            return Result.failure()
        }

        return try {
            val output = when (type) {
                "ADB_SHELL" -> {
                    val r = ShellExecutor.run(command)
                    if (r.isSuccess) r.stdout.ifEmpty { "(completed)" } else r.stderr
                }
                "ANDROID_INTENT" -> {
                    // Do not route persisted intent data through a shell: a URL
                    // containing quotes or separators could become command
                    // injection. Dispatch the Android intent directly instead.
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(command)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    applicationContext.startActivity(intent)
                    "Intent dispatched"
                }
                else -> {
                    Log.w(TAG, "Unknown command type: $type")
                    return Result.failure()
                }
            }
            Log.d(TAG, "Scheduled task completed: success=${output.isNotBlank()}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled task failed", e)
            Result.retry()
        }
    }
}
