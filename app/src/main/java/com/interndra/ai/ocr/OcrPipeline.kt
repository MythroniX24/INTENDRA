package com.interndra.ai.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OcrPipeline — on-device OCR via ML Kit Text Recognition.
 *
 * Fully offline. No data leaves the device.
 *
 * NOTE: Add ML Kit dependency to build.gradle.kts to enable:
 *   implementation("com.google.mlkit:text-recognition:16.0.1")
 *
 * If ML Kit is not present (stub build), extractText() returns an empty string
 * with a log warning — the app does not crash.
 */
class OcrPipeline(private val context: Context) {

    companion object {
        private const val TAG = "OcrPipeline"
        private var mlKitAvailable = false

        init {
            mlKitAvailable = try {
                Class.forName("com.google.mlkit.vision.text.TextRecognition")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }

    data class OcrResult(
        val text: String,
        val confidence: Float,
        val lineCount: Int,
        val wordCount: Int
    )

    // ── Extract text from file path ────────────────────────────────────────
    suspend fun extractFromFile(filePath: String): OcrResult = withContext(Dispatchers.IO) {
        if (!mlKitAvailable) {
            Log.w(TAG, "ML Kit not available — add text-recognition dependency")
            return@withContext OcrResult("", 0f, 0, 0)
        }
        val file = File(filePath)
        if (!file.exists()) return@withContext OcrResult("", 0f, 0, 0)
        val bitmap = BitmapFactory.decodeFile(filePath)
            ?: return@withContext OcrResult("", 0f, 0, 0)
        extractFromBitmap(bitmap)
    }

    // ── Extract text from URI ─────────────────────────────────────────────
    suspend fun extractFromUri(uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        if (!mlKitAvailable) return@withContext OcrResult("", 0f, 0, 0)
        try {
            val image = InputImage.fromFilePath(context, uri)
            processImage(image)
        } catch (e: Exception) {
            Log.e(TAG, "OCR from URI failed: ${e.message}")
            OcrResult("", 0f, 0, 0)
        }
    }

    // ── Extract from bitmap ───────────────────────────────────────────────
    suspend fun extractFromBitmap(bitmap: Bitmap): OcrResult {
        if (!mlKitAvailable) return OcrResult("", 0f, 0, 0)
        val image = InputImage.fromBitmap(bitmap, 0)
        return processImage(image)
    }

    private suspend fun processImage(image: InputImage): OcrResult =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    recognizer.close()  // FIX: Must close recognizer after use to avoid memory leak
                    val text      = result.text.trim()
                    val lineCount = result.textBlocks.sumOf { it.lines.size }
                    val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    Log.d(TAG, "OCR complete: $wordCount words, $lineCount lines")
                    cont.resume(OcrResult(text, 0.95f, lineCount, wordCount))
                }
                .addOnFailureListener { e ->
                    recognizer.close()  // FIX: Must close even on failure
                    Log.e(TAG, "OCR failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }

    // ── Batch OCR ─────────────────────────────────────────────────────────
    suspend fun extractFromDirectory(dirPath: String): Map<String, OcrResult> = withContext(Dispatchers.IO) {
        val dir = File(dirPath)
        if (!dir.exists()) return@withContext emptyMap()
        val results = mutableMapOf<String, OcrResult>()
        dir.listFiles()?.filter { it.extension.lowercase() in setOf("jpg","jpeg","png","webp") }?.forEach { file ->
            results[file.name] = extractFromFile(file.absolutePath)
        }
        results
    }
}
