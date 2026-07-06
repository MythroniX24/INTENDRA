package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.service.TermuxBridge

/**
 * PackageManagerPlugin — unified interface for pkg/pip/npm package management.
 *
 * Auto-detects the right package manager based on context.
 * Supports: install, update, upgrade, list, search, uninstall, info.
 */
class PackageManagerPlugin(context: Context) : IPlugin {

    companion object {
        private const val TAG = "PackageManagerPlugin"
        private const val CMD_PREFIX = "pkg:"
    }

    private val bridge = TermuxBridge(context)
    private var initialized = false

    override val id: String = "pkgmgr"
    override val name: String = "Package Manager"
    override val description: String = "Unified interface for pkg/pip/npm package management"
    override val version: String = "2.0.0"
    override val author: String = "INTERNDRA"

    override suspend fun initialize(context: Context): Boolean {
        initialized = bridge.isTermuxInstalled()
        return true
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "${CMD_PREFIX}install",
        "${CMD_PREFIX}update",
        "${CMD_PREFIX}upgrade",
        "${CMD_PREFIX}list",
        "${CMD_PREFIX}search",
        "${CMD_PREFIX}uninstall",
        "${CMD_PREFIX}info",
        "${CMD_PREFIX}detect"
    )

    override suspend fun execute(command: String, args: Map<String, String>): PluginResult {
        if (!initialized) {
            return PluginResult(false, "", error = "Termux required for package management")
        }
        return try {
            when (command.removePrefix(CMD_PREFIX)) {
                "install" -> install(args)
                "update" -> update()
                "upgrade" -> upgrade(args)
                "list" -> list(args)
                "search" -> search(args)
                "uninstall" -> uninstall(args)
                "info" -> info(args)
                "detect" -> detect()
                else -> PluginResult(false, "", error = "Unknown pkg command: $command")
            }
        } catch (e: Exception) {
            PluginResult(false, "", error = "Package manager error: ${e.message}")
        }
    }

    /**
     * Auto-detect which package managers are available.
     */
    private suspend fun detect(): PluginResult {
        val checks = listOf(
            "pkg" to "pkg --version 2>&1",
            "pip" to "pip --version 2>&1",
            "pip3" to "pip3 --version 2>&1",
            "npm" to "npm --version 2>&1",
            "node" to "node --version 2>&1",
            "python3" to "python3 --version 2>&1",
            "git" to "git --version 2>&1"
        )
        val sb = StringBuilder()
        sb.appendLine("📦 Available Package Managers")
        sb.appendLine("─".repeat(30))
        for ((name, cmd) in checks) {
            val result = bridge.executeShell("which $name 2>/dev/null | head -1")
            if (result.isSuccess && result.stdout.isNotBlank()) {
                val ver = bridge.executeShell("$name --version 2>&1 | head -1")
                val version = if (ver.isSuccess) ver.stdout.trim() else "(unknown)"
                sb.appendLine("✅ $name  $version")
            } else {
                sb.appendLine("❌ $name  (not installed)")
            }
        }
        return PluginResult(success = true, output = sb.toString().trimEnd())
    }

    /**
     * Install a package. Auto-detects pkg vs pip vs npm based on args.
     */
    private suspend fun install(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: return PluginResult(false, "", error = "Missing 'name'")
        val type = args["type"] ?: autoDetectType(name)

        val result = when (type) {
            "pip" -> bridge.executeShell("pip install $name 2>&1 || pip3 install $name 2>&1", timeoutMs = 120_000L)
            "npm" -> bridge.executeShell("npm install $name 2>&1", timeoutMs = 120_000L)
            else -> bridge.executeShell("pkg install -y $name 2>&1", timeoutMs = 120_000L)
        }

        return PluginResult(
            success = result.isSuccess,
            output = "${type} install $name\n${result.stdout}",
            error = result.stderr
        )
    }

    /**
     * Update package lists (pkg update only).
     */
    private suspend fun update(): PluginResult {
        val result = bridge.executeShell("pkg update -y 2>&1", timeoutMs = 120_000L)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    /**
     * Upgrade packages. Supports "all" or specific package.
     */
    private suspend fun upgrade(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: "all"
        val cmd = when (name) {
            "pip" -> "pip install --upgrade pip 2>&1 || pip3 install --upgrade pip 2>&1"
            "all" -> "pkg upgrade -y 2>&1"
            else -> "pkg install -y $name 2>&1"
        }
        val result = bridge.executeShell(cmd, timeoutMs = 180_000L)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    /**
     * List installed packages. Type can be "all", "pkg", "pip", "npm".
     */
    private suspend fun list(args: Map<String, String>): PluginResult {
        val type = args["type"] ?: "all"
        val sb = StringBuilder()

        if (type in listOf("all", "pkg")) {
            val pkgResult = bridge.executeShell("pkg list-installed 2>&1 | head -40")
            if (pkgResult.isSuccess) {
                sb.appendLine("📦 pkg packages:")
                sb.appendLine(pkgResult.stdout.take(500))
                sb.appendLine()
            }
        }
        if (type in listOf("all", "pip")) {
            val pipResult = bridge.executeShell("pip list 2>&1 | head -30 || pip3 list 2>&1 | head -30")
            if (pipResult.isSuccess) {
                sb.appendLine("🐍 pip packages:")
                sb.appendLine(pipResult.stdout.take(500))
                sb.appendLine()
            }
        }
        if (type in listOf("all", "npm")) {
            val npmResult = bridge.executeShell("npm list --depth=0 2>&1 | head -30")
            if (npmResult.isSuccess) {
                sb.appendLine("📦 npm packages:")
                sb.appendLine(npmResult.stdout.take(500))
            }
        }

        val output = sb.toString().ifEmpty { "No package managers found or empty" }
        return PluginResult(success = true, output = output)
    }

    private suspend fun search(args: Map<String, String>): PluginResult {
        val query = args["query"] ?: return PluginResult(false, "", error = "Missing 'query'")
        val result = bridge.executeShell("pkg search $query 2>&1 | head -30")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun uninstall(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: return PluginResult(false, "", error = "Missing 'name'")
        val type = args["type"] ?: "pkg"
        val result = when (type) {
            "pip" -> bridge.executeShell("pip uninstall -y $name 2>&1 || pip3 uninstall -y $name 2>&1")
            "npm" -> bridge.executeShell("npm uninstall $name 2>&1")
            else -> bridge.executeShell("pkg uninstall $name 2>&1")
        }
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun info(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: return PluginResult(false, "", error = "Missing 'name'")
        // Try pkg show first, then pip show
        val pkgResult = bridge.executeShell("pkg show $name 2>&1 | head -30")
        if (pkgResult.isSuccess && !pkgResult.stderr.contains("not found")) {
            return PluginResult(success = true, output = pkgResult.stdout)
        }
        val pipResult = bridge.executeShell("pip show $name 2>&1 || pip3 show $name 2>&1")
        return PluginResult(success = pipResult.isSuccess, output = pipResult.stdout, error = pipResult.stderr)
    }

    /**
     * Auto-detect package manager from package name patterns.
     */
    private fun autoDetectType(pkgName: String): String = when {
        pkgName.startsWith("lib") || pkgName.startsWith("python-") -> "pkg"
        pkgName.any { it.isUpperCase() } -> "npm"  // React packages often use PascalCase
        pkgName in knownPipPackages -> "pip"
        pkgName in knownNpmPackages -> "npm"
        else -> "pkg"
    }

    private val knownPipPackages = setOf(
        "requests", "numpy", "pandas", "flask", "django", "scipy",
        "matplotlib", "tensorflow", "torch", "beautifulsoup4",
        "selenium", "fastapi", "pytest", "click", "rich", "httpx"
    )

    private val knownNpmPackages = setOf(
        "react", "vue", "next", "express", "lodash", "axios",
        "chalk", "commander", "inquirer", "ora", "typescript",
        "tailwindcss", "prettier", "eslint", "webpack", "vite",
        "babel", "postcss", "autoprefixer", "nodemon", "pm2"
    )

    override fun teardown() {
        bridge.unregisterReceiver()
    }
}
