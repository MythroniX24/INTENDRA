package com.interndra.services

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.interndra.ai.SafetyEngine
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

    private val safety = SafetyEngine()

    override fun doWork(): Result {
        val type    = inputData.getString("TYPE")    ?: return Result.failure()
        val command = inputData.getString("COMMAND") ?: return Result.failure()

        Log.d(TAG, "Scheduled task running: type=$type cmd=${command.take(60)}")

        // Re-run safety check before executing stored command
        val report = safety.validate(command)
        if (report.result == SafetyEngine.ValidationResult.BLOCKED) {
            Log.w(TAG, "Scheduled command blocked by safety engine: ${report.reason}")
            return Result.failure()
        }

        return try {
            val output = when (type) {
                "ADB_SHELL" -> {
                    val r = ShellExecutor.run(command)
                    if (r.isSuccess) r.stdout.ifEmpty { "(completed)" } else r.stderr
                }
                "ANDROID_INTENT" -> {
                    val r = ShellExecutor.run("am start -a android.intent.action.VIEW -d \"$command\"")
                    if (r.isSuccess) r.stdout.ifEmpty { "(completed)" } else r.stderr
                }
                else -> {
                    Log.w(TAG, "Unknown command type: $type")
                    return Result.failure()
                }
            }
            Log.d(TAG, "Scheduled task result: ${output.take(100)}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled task failed", e)
            Result.retry()
        }
    }
}
