package com.interndra.service

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * TermuxBootstrapInstaller — Downloads and extracts the Termux bootstrap
 * environment into the app's private directory (proot mode) or into
 * /data/local/tmp/ (Shizuku mode).
 *
 * ## Why embedded Termux?
 * Instead of requiring the user to install Termux separately, we download
 * the official Termux bootstrap archive (used by Termux itself on first
 * launch) and extract it into our own directory. This gives us a full
 * Linux environment (bash, apt, python, git, node, pip, etc.) without
 * any external dependency.
 *
 * ## Execution Modes
 * 1. **Shizuku mode** — Bootstrap is extracted to /data/local/tmp/intendra/termux/
 *    via Shizuku ADB shell. Commands run with ADB (UID 2000) or root (UID 0)
 *    privileges, allowing full system access.
 * 2. **Proot mode** — Bootstrap is extracted to app's private data directory.
 *    Commands run via proot (user-space chroot). Slower but works without
 *    Shizuku on any device.
 *
 * ## Bootstrap Source
 * The bootstrap is the same one Termux uses internally, downloaded from:
 * https://github.com/termux/termux-packages/releases
 * It contains: bash, coreutils, apt, dpkg, tar, curl, and SSL certs (~25MB zip).
 *
 * ## After Installation
 * The environment is ready to:
 * - pkg install python git nodejs (via apt/dpkg from Termux repo)
 * - pip install, npm install, git clone
 * - Run any Linux command via bash
 */
class TermuxBootstrapInstaller(
    private val context: Context,
    private val shizukuShell: ShizukuShell
) {
    companion object {
        private const val TAG = "TermuxInstaller"

        // Latest bootstrap version — auto-updating URL
        private const val BOOTSTRAP_VERSION = "2026.07.19-r1%2Bapt.android-7"
        private const val BOOTSTRAP_BASE = "https://github.com/termux/termux-packages/releases/download"

        // Architecture mapping: Android ABI -> Termux arch name
        private val ARCH_MAP = mapOf(
            "arm64-v8a"  to "aarch64",
            "armeabi-v7a" to "arm",
            "x86"        to "i686",
            "x86_64"     to "x86_64"
        )

        // Target directories
        const val SHIZUKU_PREFIX = "/data/local/tmp/intendra/termux"

        // File marker to indicate successful installation
        private const val INSTALL_MARKER = ".installed_v1"

        // Timeouts
        private const val DOWNLOAD_TIMEOUT_MS = 180_000L   // 3 min
        private const val EXTRACT_TIMEOUT_MS  = 120_000L   // 2 min
        private const val VERIFY_TIMEOUT_MS   = 30_000L    // 30 sec
        private const val INSTALL_TIMEOUT_MS  = 300_000L   // 5 min (for pkg install)
    }

    /** The execution mode determined at install time. */
    enum class Mode {
        SHIZUKU,  // Bootstrap in /data/local/tmp/, run via Shizuku ADB shell
        PROOT,    // Bootstrap in app private dir, run via proot
        NONE      // Installation failed
    }

    /** Result of the installation process. */
    data class InstallResult(
        val success: Boolean,
        val mode: Mode,
        val prefix: String,
        val error: String? = null
    )

    /** Check if the Termux environment is already installed. */
    fun isInstalled(): Boolean {
        // Check both possible locations
        if (isShizukuInstalled()) return true
        if (isProotInstalled()) return true
        return false
    }

    /** Check if Shizuku mode installation exists. */
    fun isShizukuInstalled(): Boolean {
        val marker = "$SHIZUKU_PREFIX/$INSTALL_MARKER"
        val result = shizukuShell.executeBlocking(
            "test -f '$marker' && cat '$marker' || echo 'not_installed'",
            VERIFY_TIMEOUT_MS
        )
        return result.isSuccess && result.stdout.trim().let { 
            it.startsWith("ok") || it.startsWith("v2")
        }
    }

    /** Check if proot mode installation exists. */
    fun isProotInstalled(): Boolean {
        val markerFile = File(context.filesDir, "termux/$INSTALL_MARKER")
        return markerFile.exists() && markerFile.readText().trim().let {
            it.startsWith("ok") || it.startsWith("v2")
        }
    }

    /**
     * Check which arch we need. Requires at least one arch that produces a
     * valid Termux arch name.
     */
    private fun detectArch(): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            val arch = ARCH_MAP[abi]
            if (arch != null) return arch
        }
        return null
    }

    /** Build the download URL for the bootstrap zip. */
    private fun bootstrapUrl(arch: String): String {
        val encodedVersion = BOOTSTRAP_VERSION // already URL-encoded
        return "$BOOTSTRAP_BASE/bootstrap-$encodedVersion/bootstrap-$arch.zip"
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Install the Termux bootstrap. Returns an [InstallResult] indicating
     * success/failure and the mode (Shizuku/proot) that was used.
     *
     * @param progressCallback Called with status messages (e.g., "Downloading...")
     * @param setupPackages If true, also installs common packages (python, git)
     */
    suspend fun install(
        progressCallback: ((String) -> Unit)? = null,
        setupPackages: Boolean = false
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            // ── 1. Already installed? ──────────────────────────────
            if (isInstalled()) {
                val mode = when {
                    isShizukuInstalled() -> Mode.SHIZUKU
                    isProotInstalled() -> Mode.PROOT
                    else -> Mode.NONE
                }
                val prefix = when (mode) {
                    Mode.SHIZUKU -> SHIZUKU_PREFIX
                    Mode.PROOT -> File(context.filesDir, "termux").absolutePath
                    Mode.NONE -> ""
                }
                progressCallback?.invoke("✅ Termux environment already installed ($mode mode)")
                return@withContext InstallResult(true, mode, prefix)
            }

            // ── 2. Detect architecture ─────────────────────────────
            val arch = detectArch()
            if (arch == null) {
                val err = "Unsupported device architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}"
                Log.e(TAG, err)
                return@withContext InstallResult(false, Mode.NONE, "", err)
            }
            progressCallback?.invoke("📱 Architecture detected: $arch")

            // ── 3. Download bootstrap ──────────────────────────────
            val url = bootstrapUrl(arch)
            progressCallback?.invoke("⬇️ Downloading Termux bootstrap (~25MB)...")
            Log.i(TAG, "Downloading bootstrap from: $url")

            val zipBytes = downloadBootstrap(url) ?: run {
                // Retry once
                progressCallback?.invoke("🔄 Retrying download...")
                downloadBootstrap(url)
            }
            if (zipBytes == null) {
                val err = "Failed to download Termux bootstrap (check internet connection)"
                Log.e(TAG, err)
                return@withContext InstallResult(false, Mode.NONE, "", err)
            }
            Log.i(TAG, "Downloaded ${zipBytes.size} bytes")

            // ── 4. Try Shizuku mode first ──────────────────────────
            if (shizukuShell.isElevatedAvailable) {
                progressCallback?.invoke("🔑 Installing via Shizuku (ADB shell)...")
                val shizukuResult = installShizukuMode(zipBytes, arch, progressCallback)
                if (shizukuResult.success) {
                    return@withContext shizukuResult
                }
                Log.w(TAG, "Shizuku install failed, falling back to proot: ${shizukuResult.error}")
                progressCallback?.invoke("⚠️ Shizuku install failed, using fallback...")
            }

            // ── 5. Proot fallback ──────────────────────────────────
            progressCallback?.invoke("📦 Installing via proot mode...")
            val prootResult = installProotMode(zipBytes, progressCallback)
            if (prootResult.success) {
                return@withContext prootResult
            }

            // ── 6. Both failed ─────────────────────────────────────
            InstallResult(false, Mode.NONE, "", prootResult.error ?: "All installation methods failed")

        } catch (e: Exception) {
            Log.e(TAG, "Installation failed: ${e.message}", e)
            InstallResult(false, Mode.NONE, "", "Installation error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DOWNLOAD
    // ══════════════════════════════════════════════════════════════════════

    /** Download the bootstrap zip using OkHttp. Returns null on failure. */
    private suspend fun downloadBootstrap(url: String): ByteArray? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: HTTP ${response.code}")
                return null
            }
            response.body?.bytes()
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SHIZUKU MODE — extract to /data/local/tmp/intendra/termux/
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Install bootstrap via Shizuku.
     * Strategy: Write zip data to /data/local/tmp/ via Shizuku shell heredoc,
     * then extract using toybox unzip or busybox.
     */
    private suspend fun installShizukuMode(
        zipBytes: ByteArray,
        arch: String,
        progress: ((String) -> Unit)?
    ): InstallResult {
        try {
            val prefix = SHIZUKU_PREFIX

            // ── Step A: Create directories ──────────────────────────
            progress?.invoke("📁 Creating directories...")
            var result = shizukuShell.executeBlocking(
                "mkdir -p '$prefix/home' '$prefix/usr'",
                EXTRACT_TIMEOUT_MS
            )
            if (!result.isSuccess) {
                return InstallResult(false, Mode.SHIZUKU, "", 
                    "Cannot create directories: ${result.stderr}")
            }

            // ── Step B: Write bootstrap zip to /data/local/tmp/ ─────
            progress?.invoke("💾 Writing bootstrap archive...")
            // Write via base64 to avoid shell escaping issues
            val base64Str = android.util.Base64.encodeToString(zipBytes, android.util.Base64.NO_WRAP)
            result = shizukuShell.executeBlocking(
                "echo '$base64Str' | base64 -d > /data/local/tmp/intendra_bootstrap.zip 2>&1",
                EXTRACT_TIMEOUT_MS
            )
            if (!result.isSuccess) {
                Log.w(TAG, "Base64 write failed: ${result.stderr}")
                // Try alternative: write in chunks via multiple echo commands
                val chunkResult = writeZipInChunksShizuku(zipBytes)
                if (!chunkResult) {
                    return InstallResult(false, Mode.SHIZUKU, "",
                        "Failed to write bootstrap zip via Shizuku")
                }
            }

            // ── Step C: Extract the zip ─────────────────────────────
            progress?.invoke("📦 Extracting bootstrap (this may take a minute)...")
            result = shizukuShell.executeBlocking(
                "cd '$prefix' && " +
                "unzip -o /data/local/tmp/intendra_bootstrap.zip 2>&1 || " +
                "toybox unzip -o /data/local/tmp/intendra_bootstrap.zip 2>&1 || " +
                "busybox unzip -o /data/local/tmp/intendra_bootstrap.zip 2>&1",
                EXTRACT_TIMEOUT_MS
            )
            if (!result.isSuccess) {
                Log.w(TAG, "unzip failed: ${result.stderr}")
                // Fall back to app-side extraction + copy
                return installShizukuViaCopy(zipBytes, prefix, progress)
            }

            // ── Step D: Handle SYMLINKS.txt ─────────────────────────
            progress?.invoke("🔗 Setting up symlinks...")
            setupSymlinksShizuku(prefix)

            // ── Step E: Verify installation ─────────────────────────
            progress?.invoke("✅ Verifying installation...")
            if (!verifyShizukuInstall(prefix)) {
                return InstallResult(false, Mode.SHIZUKU, "",
                    "Installation verification failed")
            }

            // ── Step F: Create install marker ───────────────────────
            shizukuShell.executeBlocking(
                "echo 'ok_v2' > '$prefix/$INSTALL_MARKER'",
                VERIFY_TIMEOUT_MS
            )

            // ── Step G: Cleanup ─────────────────────────────────────
            shizukuShell.executeBlocking(
                "rm -f /data/local/tmp/intendra_bootstrap.zip",
                VERIFY_TIMEOUT_MS
            )

            Log.i(TAG, "✅ Shizuku-mode Termux installed at $prefix")
            return InstallResult(true, Mode.SHIZUKU, prefix)

        } catch (e: Exception) {
            Log.e(TAG, "Shizuku install error: ${e.message}")
            InstallResult(false, Mode.SHIZUKU, "", "Shizuku error: ${e.message}")
        }
    }

    /** Fallback: extract zip in-app, then copy files via Shizuku. */
    private suspend fun installShizukuViaCopy(
        zipBytes: ByteArray,
        targetPrefix: String,
        progress: ((String) -> Unit)?
    ): InstallResult {
        try {
            progress?.invoke("📦 Extracting (in-app)...")

            // Extract to app's cache dir first
            val extractDir = File(context.cacheDir, "termux_bootstrap")
            extractDir.deleteRecursively()
            extractDir.mkdirs()
            extractZip(zipBytes, extractDir)

            // Collect all files to copy
            val files = extractDir.walkTopDown().filter { it.isFile }.toList()
            val totalFiles = files.size
            Log.i(TAG, "Extracted $totalFiles files, copying to $targetPrefix...")

            // Copy files via Shizuku in batches
            val batchSize = 50
            files.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                val start = batchIndex * batchSize + 1
                val end = (start + batch.size - 1).coerceAtMost(totalFiles)
                progress?.invoke("📤 Copying files ($start-$end of $totalFiles)...")

                // Build a single shell command to copy all files in this batch
                val cpCommands = batch.joinToString(" && ") { file ->
                    val relativePath = file.relativeTo(extractDir).path
                    val targetFile = "$targetPrefix/$relativePath"
                    // Base64 encode the file content
                    val b64 = android.util.Base64.encodeToString(
                        file.readBytes(), android.util.Base64.NO_WRAP
                    )
                    "echo '$b64' | base64 -d > '$targetFile' 2>/dev/null"
                }

                val result = shizukuShell.executeBlocking(cpCommands, EXTRACT_TIMEOUT_MS)
                if (!result.isSuccess) {
                    Log.w(TAG, "Batch $batchIndex copy failed: ${result.stderr}")
                }
            }

            // Set permissions
            progress?.invoke("🔧 Setting permissions...")
            shizukuShell.executeBlocking(
                "chmod -R 755 '$targetPrefix/usr/bin' '$targetPrefix/usr/lib' 2>/dev/null; " +
                "chmod 644 '$targetPrefix/usr/lib/'*.so 2>/dev/null",
                VERIFY_TIMEOUT_MS
            )

            // Setup symlinks
            setupSymlinksShizuku(targetPrefix)

            // Cleanup
            extractDir.deleteRecursively()

            if (!verifyShizukuInstall(targetPrefix)) {
                return InstallResult(false, Mode.SHIZUKU, "",
                    "Copied installation verification failed")
            }

            shizukuShell.executeBlocking(
                "echo 'ok_v2' > '$targetPrefix/$INSTALL_MARKER'",
                VERIFY_TIMEOUT_MS
            )

            return InstallResult(true, Mode.SHIZUKU, targetPrefix)

        } catch (e: Exception) {
            Log.e(TAG, "Shizuku copy error: ${e.message}")
            InstallResult(false, Mode.SHIZUKU, "", "Copy error: ${e.message}")
        }
    }

    /** Write zip file to /data/local/tmp/ via Shizuku in base64 chunks. */
    private suspend fun writeZipInChunksShizuku(zipBytes: ByteArray): Boolean {
        return try {
            val b64 = android.util.Base64.encodeToString(zipBytes, android.util.Base64.NO_WRAP)
            // Split into ~10KB chunks and write them
            val chunkSize = 8_000
            val totalChunks = (b64.length + chunkSize - 1) / chunkSize

            // Start with clean file
            shizukuShell.executeBlocking(": > /data/local/tmp/intendra_bootstrap.zip", VERIFY_TIMEOUT_MS)

            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = (start + chunkSize).coerceAtMost(b64.length)
                val chunk = b64.substring(start, end)

                val result = shizukuShell.executeBlocking(
                    "printf '%s' '$chunk' >> /data/local/tmp/intendra_bootstrap_b64.tmp 2>&1",
                    VERIFY_TIMEOUT_MS
                )
                if (!result.isSuccess) return false
            }

            // Decode
            shizukuShell.executeBlocking(
                "cat /data/local/tmp/intendra_bootstrap_b64.tmp | base64 -d > /data/local/tmp/intendra_bootstrap.zip 2>&1 && " +
                "rm -f /data/local/tmp/intendra_bootstrap_b64.tmp",
                EXTRACT_TIMEOUT_MS
            )?.let { return it.isSuccess }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Chunk write failed: ${e.message}")
            false
        }
    }

    /** Handle SYMLINKS.txt in Shizuku mode. */
    private suspend fun setupSymlinksShizuku(prefix: String) {
        val result = shizukuShell.executeBlocking(
            "cat '$prefix/SYMLINKS.txt' 2>/dev/null",
            VERIFY_TIMEOUT_MS
        )
        if (!result.isSuccess) return // No symlinks file

        val lines = result.stdout.lines()
        for (line in lines) {
            val parts = line.trim().split("←|→|←|←").map { it.trim() }
            if (parts.size >= 2) {
                val target = parts[0]
                val linkName = parts[1]
                shizukuShell.executeBlocking(
                    "ln -sf '$target' '$prefix/$linkName' 2>/dev/null",
                    VERIFY_TIMEOUT_MS
                )
            }
        }
    }

    /** Verify Shizuku mode installation by checking bash and apt. */
    private suspend fun verifyShizukuInstall(prefix: String): Boolean {
        val checks = listOf(
            "test -f '$prefix/usr/bin/bash'",
            "test -f '$prefix/usr/lib/libtermux-exec.so'",
            "test -d '$prefix/usr/var/lib/dpkg'",
            "test -d '$prefix/home'"
        )
        for (check in checks) {
            val result = shizukuShell.executeBlocking(check, VERIFY_TIMEOUT_MS)
            if (!result.isSuccess) {
                Log.w(TAG, "Verification failed: $check")
                return false
            }
        }
        return true
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PROOT MODE — extract to app private dir
    // ══════════════════════════════════════════════════════════════════════

    /** Install bootstrap to app's private directory for proot mode. */
    private suspend fun installProotMode(
        zipBytes: ByteArray,
        progress: ((String) -> Unit)?
    ): InstallResult {
        try {
            val prefixDir = File(context.filesDir, "termux")
            val usrDir = File(prefixDir, "usr")

            // Clean any partial installation
            if (prefixDir.exists()) {
                prefixDir.deleteRecursively()
            }
            prefixDir.mkdirs()
            File(prefixDir, "home").mkdirs()
            usrDir.mkdirs()

            // ── Extract zip ─────────────────────────────────────────
            progress?.invoke("📦 Extracting bootstrap files...")
            extractZip(zipBytes, prefixDir)

            // ── Handle SYMLINKS.txt ─────────────────────────────────
            progress?.invoke("🔗 Setting up symlinks...")
            setupSymlinksProot(prefixDir)

            // ── Set permissions ──────────────────────────────────────
            progress?.invoke("🔧 Setting permissions...")
            File(usrDir, "bin").let { bin ->
                if (bin.exists()) bin.walkTopDown().forEach { 
                    if (it.isFile) it.setExecutable(true, false) 
                }
            }
            File(usrDir, "lib").let { lib ->
                if (lib.exists()) lib.walkTopDown().forEach { 
                    if (it.isFile && it.name.endsWith(".so")) 
                        it.setExecutable(false, false) 
                }
            }

            // ── Verify ──────────────────────────────────────────────
            progress?.invoke("✅ Verifying installation...")
            if (!verifyProotInstall(prefixDir)) {
                return InstallResult(false, Mode.PROOT, "",
                    "Installation verification failed")
            }

            // ── Write marker ────────────────────────────────────────
            File(prefixDir, INSTALL_MARKER).writeText("ok_v2")

            // Fetch proot binary
            progress?.invoke("⬇️ Downloading proot binary...")
            if (!downloadProotBinary()) {
                Log.w(TAG, "Proot binary download failed — only Shizuku mode will work")
            }

            val prefix = prefixDir.absolutePath
            Log.i(TAG, "✅ Proot-mode Termux installed at $prefix")
            return InstallResult(true, Mode.PROOT, prefix)

        } catch (e: Exception) {
            Log.e(TAG, "Proot install error: ${e.message}")
            InstallResult(false, Mode.PROOT, "", "Proot error: ${e.message}")
        }
    }

    /** Extract a ZIP file from bytes into the target directory. */
    private fun extractZip(zipBytes: ByteArray, targetDir: File) {
        val buffer = ByteArray(8192)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(targetDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var bytesRead: Int
                        while (zis.read(buffer).also { bytesRead = it } >= 0) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                    // Preserve executable permission
                    if (entry.name.startsWith("usr/bin/") || entry.name.startsWith("bin/")) {
                        outFile.setExecutable(true, false)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Handle SYMLINKS.txt for proot mode. */
    private fun setupSymlinksProot(prefixDir: File) {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.exists()) return

        try {
            val lines = symlinksFile.readLines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue

                // Format: "target ← linkname" or "target → linkname"
                val parts = trimmed.split("←|→".toRegex()).map { it.trim() }
                if (parts.size >= 2) {
                    val target = File(prefixDir, parts[0])
                    val linkFile = File(prefixDir, parts[1])
                    if (target.exists()) {
                        try {
                            linkFile.parentFile?.mkdirs()
                            linkFile.delete()
                            // Create relative symlink
                            val relTarget = target.relativeTo(linkFile.parentFile!!)
                            // On Android, we can create symlinks using ln
                            Runtime.getRuntime().exec(arrayOf(
                                "ln", "-sf", relTarget.path, linkFile.absolutePath
                            )).waitFor()
                        } catch (e: Exception) {
                            // If symlink fails, copy the file instead
                            try {
                                target.copyTo(linkFile, overwrite = true)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Symlink setup failed: ${e.message}")
        }
    }

    /** Verify proot mode installation. */
    private fun verifyProotInstall(prefixDir: File): Boolean {
        val checks = listOf(
            File(prefixDir, "usr/bin/bash"),
            File(prefixDir, "usr/lib/libtermux-exec.so"),
            File(prefixDir, "usr/var/lib/dpkg"),
            File(prefixDir, "home")
        )
        return checks.all { it.exists() }
    }

    /** Download a static proot binary into the app's proot dir. */
    private suspend fun downloadProotBinary(): Boolean {
        return try {
            val prootDir = File(context.filesDir, "proot")
            prootDir.mkdirs()

            // Determine arch
            val arch = when {
                Build.SUPPORTED_ABIS.any { it.startsWith("arm64") || it.startsWith("aarch64") } -> "aarch64"
                Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") || it.startsWith("arm") } -> "arm"
                Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
                else -> return false
            }

            // Download static proot binary from Termux's repo
            val prootUrl = "https://github.com/termux/proot/releases/download/v5.4.0/proot-$arch"
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(prootUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return false

            val prootFile = File(prootDir, "proot")
            prootFile.writeBytes(response.body!!.bytes())
            prootFile.setExecutable(true, false)

            Log.i(TAG, "Proot binary downloaded: ${prootFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Proot download failed: ${e.message}")
            false
        }
    }

    /** Check if proot binary is available. */
    fun hasProotBinary(): Boolean {
        val prootFile = File(context.filesDir, "proot/proot")
        return prootFile.exists() && prootFile.canExecute()
    }

    /** Get the proot binary path. */
    fun getProotPath(): String? {
        val prootFile = File(context.filesDir, "proot/proot")
        return if (prootFile.exists() && prootFile.canExecute()) prootFile.absolutePath else null
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CLEANUP
    // ══════════════════════════════════════════════════════════════════════

    /** Remove the installed Termux environment. */
    suspend fun uninstall(progress: ((String) -> Unit)? = null) {
        progress?.invoke("🗑️ Removing Termux environment...")

        if (isShizukuInstalled()) {
            shizukuShell.executeBlocking(
                "rm -rf '$SHIZUKU_PREFIX' 2>/dev/null",
                EXTRACT_TIMEOUT_MS
            )
        }

        val termuxDir = File(context.filesDir, "termux")
        if (termuxDir.exists()) {
            termuxDir.deleteRecursively()
        }

        val prootDir = File(context.filesDir, "proot")
        if (prootDir.exists()) {
            prootDir.deleteRecursively()
        }

        progress?.invoke("✅ Termux environment removed")
    }
}
