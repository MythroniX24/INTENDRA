package com.interndra.ai.intelligence

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DeviceIntelligence — on-device system info aggregator.
 *
 * Provides structured snapshots of battery, RAM, storage, network, and
 * running processes. All data stays local — never transmitted to cloud.
 * Used to augment AI context with real device state.
 */
class DeviceIntelligence(private val context: Context) {

    companion object {
        private const val TAG = "DeviceIntelligence"
    }

    data class DeviceSnapshot(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val batteryTemperature: Float,
        val ramUsedMb: Long,
        val ramTotalMb: Long,
        val storageFreeGb: Float,
        val storageTotalGb: Float,
        val networkType: String,
        val isOnline: Boolean,
        val cpuAbi: String,
        val androidVersion: String,
        val model: String,
        val runningAppsCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val ramUsagePercent: Int get() = if (ramTotalMb > 0) ((ramUsedMb * 100) / ramTotalMb).toInt() else 0
        val storageUsagePercent: Int get() {
            val usedGb = storageTotalGb - storageFreeGb
            return if (storageTotalGb > 0) ((usedGb / storageTotalGb) * 100).toInt() else 0
        }

        fun toPromptString(): String = buildString {
            appendLine("📱 Device State:")
            appendLine("• Battery: $batteryLevel% (${if (isCharging) "charging" else "on battery"}, ${batteryTemperature}°C)")
            appendLine("• RAM: ${ramUsedMb}MB used / ${ramTotalMb}MB total ($ramUsagePercent%)")
            appendLine("• Storage: ${"%.1f".format(storageFreeGb)}GB free / ${"%.1f".format(storageTotalGb)}GB total")
            appendLine("• Network: $networkType (${if (isOnline) "online" else "offline"})")
            appendLine("• Running apps: $runningAppsCount")
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────
    suspend fun getSnapshot(): DeviceSnapshot = withContext(Dispatchers.IO) {
        try {
            val battery = getBatteryInfo()
            val ram     = getRamInfo()
            val storage = getStorageInfo()
            val network = getNetworkInfo()
            val apps    = getRunningAppCount()

            DeviceSnapshot(
                batteryLevel        = battery.first,
                isCharging          = battery.second,
                batteryTemperature  = battery.third,
                ramUsedMb           = ram.first,
                ramTotalMb          = ram.second,
                storageFreeGb       = storage.first,
                storageTotalGb      = storage.second,
                networkType         = network.first,
                isOnline            = network.second,
                cpuAbi              = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                androidVersion      = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                model               = "${Build.MANUFACTURER} ${Build.MODEL}",
                runningAppsCount    = apps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot error: ${e.message}")
            DeviceSnapshot(0, false, 0f, 0, 0, 0f, 0f, "unknown", false, "unknown", "unknown", "unknown", 0)
        }
    }

    // ── Battery ───────────────────────────────────────────────────────────
    private fun getBatteryInfo(): Triple<Int, Boolean, Float> {
        val filter  = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent  = context.registerReceiver(null, filter) ?: return Triple(0, false, 0f)
        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp    = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val pct     = if (scale > 0) (level * 100 / scale) else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        return Triple(pct, charging, temp / 10f)
    }

    // ── RAM ───────────────────────────────────────────────────────────────
    private fun getRamInfo(): Pair<Long, Long> {
        val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / 1_048_576
        val usedMb  = (info.totalMem - info.availMem) / 1_048_576
        return Pair(usedMb, totalMb)
    }

    // ── Storage ───────────────────────────────────────────────────────────
    private fun getStorageInfo(): Pair<Float, Float> {
        val stat  = StatFs(Environment.getDataDirectory().path)
        val free  = stat.availableBlocksLong * stat.blockSizeLong
        val total = stat.blockCountLong * stat.blockSizeLong
        return Pair(free / 1_073_741_824f, total / 1_073_741_824f)
    }

    // ── Network ───────────────────────────────────────────────────────────
    private fun getNetworkInfo(): Pair<String, Boolean> {
        val cm  = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return Pair("none", false)
        val cap = cm.getNetworkCapabilities(net) ?: return Pair("none", false)
        val online = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                     cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val type = when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        return Pair(type, online)
    }

    // ── Running apps ──────────────────────────────────────────────────────
    private fun getRunningAppCount(): Int = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.size ?: 0
    } catch (e: Exception) { 0 }

    // ── Quick accessors ───────────────────────────────────────────────────
    suspend fun getBatteryLevel(): Int  = getSnapshot().batteryLevel
    suspend fun isOnline(): Boolean     = getNetworkInfo().second
    suspend fun getFreeStorageGb(): Float = getStorageInfo().first
}
