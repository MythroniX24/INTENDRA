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
 * Resumable downloader for local GGUF models.
 *
 * Downloads always go to a `.part` file. A model becomes visible to the local
 * engine only after its size and GGUF magic header are valid and the part is
 * atomically renamed to its final filename. This prevents an interrupted
 * download or an HTML error page from being loaded as a model.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
        private const val MODEL_URL_SMALL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

        const val MIN_VALID_BYTES_3B = 500L * 1024L * 1024L
        const val MIN_VALID_BYTES_SMALL = 200L * 1024L * 1024L
        const val MODEL_FILENAME = LocalAiEngine.DEFAULT_MODEL_FILENAME
        const val SMALL_MODEL_FILENAME = LocalAiEngine.SMALL_MODEL_FILENAME

        fun modelDir(context: Context) = File(context.filesDir, "models")
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val speedBps: Long = 0L
        ) : DownloadState() {
            val progress: Float get() =
                if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            val progressPercent: Int get() = (progress * 100).toInt()
            val downloadedMB: String get() = "%.1f MB".format(bytesDownloaded / 1_000_000.0)
            val totalMB: String get() = if (totalBytes > 0) "%.1f MB".format(totalBytes / 1_000_000.0) else "?"
            val speedLabel: String get() = when {
                speedBps > 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000.0)
                speedBps > 1_000 -> "%.0f KB/s".format(speedBps / 1_000.0)
                else -> "? KB/s"
            }
        }
        data class Complete(val filePath: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
        data object Cancelled : DownloadState()
    }

    @Volatile private var isCancelled = false

    fun downloadModel(useSmallModel: Boolean = false): Flow<DownloadState> = channelFlow {
        isCancelled = false
        val url = if (useSmallModel) MODEL_URL_SMALL else MODEL_URL
        val finalName = if (useSmallModel) SMALL_MODEL_FILENAME else MODEL_FILENAME
        val minBytes = if (useSmallModel) MIN_VALID_BYTES_SMALL else MIN_VALID_BYTES_3B
        val destinationDir = modelDir(context)
        val finalFile = File(destinationDir, finalName)
        val partFile = File(destinationDir, "$finalName.part")

        try {
            if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                throw IllegalStateException("Cannot create model directory")
            }
            if (ModelIntegrity.isValidGguf(finalFile, minBytes)) {
                send(DownloadState.Complete(finalFile.absolutePath))
                return@channelFlow
            }

            val existingBytes = partFile.takeIf { it.exists() }?.length() ?: 0L
            send(DownloadState.Downloading(existingBytes, -1L))
            Log.d(TAG, "Starting ${if (useSmallModel) "0.5B" else "3B"} download; resume=$existingBytes bytes")

            withContext(Dispatchers.IO) {
                val connection = openConnection(url, existingBytes)
                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("HTTP $responseCode from model host")
                    }

                    // A server that ignores Range returns 200; truncate the part
                    // instead of appending the complete response to old bytes.
                    val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                    val contentLength = connection.contentLengthLong
                    val totalBytes = when {
                        append && contentLength > 0L -> existingBytes + contentLength
                        contentLength > 0L -> contentLength
                        else -> -1L
                    }
                    var downloaded = if (append) existingBytes else 0L
                    var lastEmit = System.currentTimeMillis()
                    var lastBytes = downloaded

                    FileOutputStream(partFile, append).use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (!isCancelled) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 500L) {
                                    val elapsed = (now - lastEmit).coerceAtLeast(1L)
                                    val speed = (downloaded - lastBytes) * 1_000L / elapsed
                                    send(DownloadState.Downloading(downloaded, totalBytes, speed))
                                    lastEmit = now
                                    lastBytes = downloaded
                                }
                            }
                            output.flush()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

            if (isCancelled) {
                send(DownloadState.Cancelled)
                return@channelFlow
            }

            val finalSize = partFile.length()
            if (finalSize < minBytes) {
                // Keep a short but potentially resumable part for the next run.
                send(DownloadState.Error(
                    "Download incomplete (${finalSize / 1_000_000} MB); " +
                        "at least ${minBytes / 1_000_000} MB is required."
                ))
                return@channelFlow
            }
            if (!ModelIntegrity.isValidGguf(partFile, minBytes)) {
                partFile.delete()
                send(DownloadState.Error("Downloaded file is not a valid GGUF model"))
                return@channelFlow
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IllegalStateException("Cannot replace existing model")
            }
            if (!partFile.renameTo(finalFile)) {
                throw IllegalStateException("Cannot finalize model download")
            }
            Log.i(TAG, "Model download complete: ${finalFile.absolutePath} (${finalSize / 1_000_000} MB)")
            send(DownloadState.Complete(finalFile.absolutePath))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            send(DownloadState.Error(e.message ?: "Download failed"))
        }
    }

    private fun openConnection(url: String, existingBytes: Long): HttpURLConnection {
        var connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "INTENDRA-Android/2.1")
        if (existingBytes > 0L) connection.setRequestProperty("Range", "bytes=$existingBytes-")

        var redirects = 0
        while (redirects < 5) {
            val code = connection.responseCode
            if (code !in listOf(301, 302, 303, 307, 308)) break
            val location = connection.getHeaderField("Location") ?: break
            connection.disconnect()
            connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "INTENDRA-Android/2.1")
            if (existingBytes > 0L) connection.setRequestProperty("Range", "bytes=$existingBytes-")
            redirects++
        }
        return connection
    }

    fun cancel() {
        isCancelled = true
        Log.d(TAG, "Download cancelled")
    }

    fun isModelDownloaded(): Boolean = listOf(MODEL_FILENAME, SMALL_MODEL_FILENAME).any { name ->
        ModelIntegrity.isValidGguf(File(modelDir(context), name), minimumBytesFor(name)) ||
            LocalAiEngine.getSearchPaths(context).any { dir ->
                ModelIntegrity.isValidGguf(File(dir, name), minimumBytesFor(name))
            }
    }

    fun getModelSizeOnDisk(): String {
        val file = listOf(MODEL_FILENAME, SMALL_MODEL_FILENAME)
            .map { File(modelDir(context), it) }
            .firstOrNull { it.exists() }
        return if (file != null) "%.1f MB".format(file.length() / 1_000_000.0) else "Not downloaded"
    }

    fun deleteModel() {
        (listOf(MODEL_FILENAME, SMALL_MODEL_FILENAME) +
            LocalAiEngine.getSearchPaths(context).flatMap { dir -> listOf(MODEL_FILENAME, SMALL_MODEL_FILENAME).map { "$dir/$it" } })
            .forEach { File(it).delete(); File("$it.part").delete() }
        Log.d(TAG, "Models deleted")
    }

    private fun minimumBytesFor(name: String): Long =
        if (name == SMALL_MODEL_FILENAME) MIN_VALID_BYTES_SMALL else MIN_VALID_BYTES_3B
}

/** Lightweight local validation. A cryptographic hash can be added per catalog entry later. */
object ModelIntegrity {
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    fun isValidGguf(file: File, minimumBytes: Long): Boolean {
        if (!file.isFile || file.length() < minimumBytes) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(GGUF_MAGIC.size)
                input.read(header) == header.size && header.contentEquals(GGUF_MAGIC)
            }
        }.getOrDefault(false)
    }
}
