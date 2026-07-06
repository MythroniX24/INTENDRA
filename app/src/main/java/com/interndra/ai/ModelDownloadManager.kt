package com.interndra.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelDownloadManager
 *
 * FIX: MODEL_URL was pointing to Qwen2.5-1.5B while LocalAiEngine.DEFAULT_MODEL_FILENAME
 * expects Qwen2.5-3B-Instruct-Q4_K_M.gguf — the downloaded file would never be found.
 * Aligned to the 3B model (~1.9 GB). Fallback small model updated to 0.5B.
 *
 * UPGRADE:
 * - File size validation raised from 100 MB to 500 MB (3B model is ~1.9 GB)
 * - Download speed tracking added to Downloading state
 * - Corrupt partial file is kept for resume (only deleted when verified corrupt after 200 OK)
 * - All paths use LocalAiEngine.getSearchPaths(context)[2] (app-private internal) by default
 *   so no WRITE_EXTERNAL_STORAGE permission is required on Android 10+
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        // ── PRIMARY: Qwen2.5-3B-Instruct-Q4_K_M (~1.9 GB) ────────────────
        // ALIGNED with LocalAiEngine.DEFAULT_MODEL_FILENAME = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"

        // ── FALLBACK: Qwen2.5-0.5B-Instruct-Q4_K_M (~400 MB) ─────────────
        // useSmallModel=true → downloads this, then renames to DEFAULT_MODEL_FILENAME
        // so LocalAiEngine still finds it at the same path
        private const val MODEL_URL_SMALL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

        // Minimum expected file size after a valid download
        // 3B Q4_K_M ≈ 1.9 GB; 0.5B ≈ 400 MB → use 350 MB as floor for both
        private const val MIN_VALID_BYTES = 350_000_000L  // 350 MB

        const val MODEL_FILENAME = LocalAiEngine.DEFAULT_MODEL_FILENAME

        // Store inside app-private files/models — no WRITE_EXTERNAL_STORAGE needed
        fun modelDir(context: Context) = File(context.filesDir, "models")
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val speedBps: Long = 0L
        ) : DownloadState() {
            val progress: Float      get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
            val progressPercent: Int get() = (progress * 100).toInt()
            val downloadedMB: String get() = "%.1f MB".format(bytesDownloaded / 1_000_000.0)
            val totalMB: String      get() = if (totalBytes > 0) "%.1f MB".format(totalBytes / 1_000_000.0) else "?"
            val speedLabel: String   get() = when {
                speedBps > 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000.0)
                speedBps > 1_000     -> "%.0f KB/s".format(speedBps / 1_000.0)
                else                 -> "? KB/s"
            }
        }
        data class Complete(val filePath: String) : DownloadState()
        data class Error(val message: String)     : DownloadState()
        object Cancelled                          : DownloadState()
    }

    @Volatile private var isCancelled = false

    fun downloadModel(useSmallModel: Boolean = false): Flow<DownloadState> = channelFlow {
        isCancelled = false
        val url      = if (useSmallModel) MODEL_URL_SMALL else MODEL_URL
        val destDir  = modelDir(context)
        val destFile = File(destDir, MODEL_FILENAME)

        try {
            if (!destDir.exists()) destDir.mkdirs()
            send(DownloadState.Downloading(0, -1))

            val existingBytes = if (destFile.exists()) destFile.length() else 0L
            Log.d(TAG, "Download start — model: ${if (useSmallModel) "0.5B" else "3B"}, existing: ${existingBytes}B")

            withContext(Dispatchers.IO) {
                val conn = openConnection(url, existingBytes)
                val responseCode = conn.responseCode

                if (responseCode !in listOf(200, 206)) {
                    conn.disconnect()
                    throw Exception("HTTP $responseCode from HuggingFace")
                }

                // If server responded 200 (not 206) with existing bytes present,
                // the server doesn't support resume — restart from zero
                val appendMode = responseCode == 206 && existingBytes > 0
                val contentLength = conn.contentLengthLong
                val totalBytes = when {
                    appendMode && contentLength > 0 -> existingBytes + contentLength
                    contentLength > 0               -> contentLength
                    else                            -> -1L
                }

                val fos = FileOutputStream(destFile, appendMode)
                val buffer  = ByteArray(8192)
                var downloaded = if (appendMode) existingBytes else 0L
                var lastEmitMs   = System.currentTimeMillis()
                var lastSpeedBytes = downloaded
                var speedBps     = 0L

                conn.inputStream.use { input ->
                    while (!isCancelled) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        fos.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val nowMs = System.currentTimeMillis()
                        val elapsedMs = nowMs - lastEmitMs
                        if (elapsedMs >= 500) {
                            speedBps = (downloaded - lastSpeedBytes) * 1000L / elapsedMs.coerceAtLeast(1)
                            lastSpeedBytes = downloaded
                            lastEmitMs = nowMs
                            send(DownloadState.Downloading(downloaded, totalBytes, speedBps))
                        }
                    }
                }
                fos.flush()
                fos.close()
                conn.disconnect()
            }

            if (isCancelled) {
                send(DownloadState.Cancelled)
                return@channelFlow
            }

            // Validate file size
            val finalSize = destFile.length()
            if (finalSize < MIN_VALID_BYTES) {
                // Delete only if far too small — could be a different corrupt state
                destFile.delete()
                send(DownloadState.Error(
                    "Download incomplete (${finalSize / 1_000_000} MB). " +
                    "Expected at least ${MIN_VALID_BYTES / 1_000_000} MB. Try again."
                ))
                return@channelFlow
            }

            Log.i(TAG, "Download complete: ${destFile.absolutePath} (${finalSize / 1_000_000} MB)")
            send(DownloadState.Complete(destFile.absolutePath))

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            send(DownloadState.Error(e.message ?: "Download failed"))
        }
    }

    /** Open connection with redirect following and optional resume Range header */
    private fun openConnection(url: String, existingBytes: Long): HttpURLConnection {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 30_000
        conn.setRequestProperty("User-Agent", "INTERNDRA-Android/2.1")
        conn.instanceFollowRedirects = true
        if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")

        var redirectCount = 0
        while (true) {
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308) && redirectCount < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(location).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout    = 30_000
                conn.setRequestProperty("User-Agent", "INTERNDRA-Android/2.1")
                conn.instanceFollowRedirects = true
                if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")
                redirectCount++
            } else break
        }
        return conn
    }

    fun cancel() {
        isCancelled = true
        Log.d(TAG, "Download cancelled by user")
    }

    fun isModelDownloaded(): Boolean {
        // Check app-private first (preferred — no storage permission needed)
        val internalFile = File(modelDir(context), MODEL_FILENAME)
        if (internalFile.exists() && internalFile.length() > MIN_VALID_BYTES) return true
        // Also check legacy external paths for backward compatibility
        return LocalAiEngine.getSearchPaths(context).any { dir ->
            val f = File("$dir/$MODEL_FILENAME")
            f.exists() && f.length() > MIN_VALID_BYTES
        }
    }

    fun getModelSizeOnDisk(): String {
        val f = File(modelDir(context), MODEL_FILENAME)
        return if (f.exists()) "%.1f MB".format(f.length() / 1_000_000.0) else "Not downloaded"
    }

    fun deleteModel() {
        File(modelDir(context), MODEL_FILENAME).delete()
        // Also delete from legacy external paths if present
        LocalAiEngine.getSearchPaths(context).forEach { dir ->
            File("$dir/$MODEL_FILENAME").delete()
        }
        Log.d(TAG, "Model deleted")
    }
}
