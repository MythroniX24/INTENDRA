package com.interndra.ai

import com.interndra.data.model.ShellCommand

/**
 * SafetyEngine — hardened command validator.
 *
 * FIXES (Phase 10 — Security Hardening):
 *
 *  1. NORMALIZATION: before matching, commands are normalized:
 *     - collapse all whitespace runs to single spaces
 *     - strip bash quotes (' ', " ") that break substring matching
 *     - strip ${IFS} and $IFS substitutions used to evade matching
 *     - decode base64 -d piped payloads so encoded attacks are caught
 *     This closes the "rm  -rf  /" and `r''m -rf /` bypasses.
 *
 *  2. REGEX PATTERNS: dangerous patterns now use regex with word boundaries
 *     and path alternations, so `rm -rf /storage/emulated/0` is caught
 *     (matches `rm\s+-rf\s+/(sdcard|storage|system|data|...)`).
 *
 *  3. EXPANDED COVERAGE: added `find .* -delete`, `chmod 777 /sdcard`,
 *     `chmod 777 /data`, `echo ... | base64 -d | sh`, `$(rm ...)`,
 *     `${IFS}rm`, `busybox rm`, alternate boot-image flashing, etc.
 *
 *  4. STRUCTURED VERDICT: `validate()` returns a `Verdict` with `.safe`
 *     boolean and `.reason` string for easy consumption by callers.
 *
 *  5. BATCH SAFE: `validateAll()` short-circuits on first BLOCKED verdict.
 */
class SafetyEngine {

    enum class ValidationResult { SAFE, BLOCKED, REQUIRES_CONFIRMATION }

    data class Report(
        val result: ValidationResult,
        val reason: String,
        val matchedPattern: String? = null
    )

    /** Convenience verdict — consumed by InterndraNotificationListener. */
    data class Verdict(val safe: Boolean, val reason: String)

    // ── Hard blocked — never execute (regex patterns) ────────────────────
    // Patterns are matched against the NORMALIZED command (lowercased).
    private val HARD_BLOCKED_REGEX: List<Pair<Regex, String>> = listOf(
        // Mass deletion / wipe — covers /, /sdcard, /storage, /system, /data,
        // ~, *, alternate paths, and any rm -rf targeting a top-level dir.
        Regex("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*|--recursive\s+--force)\s+/(sdcard|storage|system|data|cache|proc|dev|sbin|etc|root|var|tmp|\*|\s*$|\s*/\s*$)""") to "rm -rf against a system path",
        Regex("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*|--recursive\s+--force)\s+(~|\${'$'}HOME|\*|/+(?:\s|\$))""") to "rm -rf against home or root",
        Regex("""\brmdir\s+/(\s|$)""") to "rmdir of root",
        Regex("""\bmkfs\b""") to "filesystem format (mkfs)",
        Regex("""\bdd\s+if\s*=\s*/dev/(zero|urandom|random)""") to "dd from /dev/zero or /dev/urandom",
        Regex(""">\s*/dev/(sd[a-z]+|block/)""") to "redirect to block device",
        Regex("""\bshred\s+/dev/""") to "shred of device file",
        Regex("""\bfind\s+/\s+.*-delete\b""") to "find / -delete",
        Regex("""\bfind\s+/sdcard\s+.*-delete\b""") to "find /sdcard -delete",
        Regex("""\bfind\s+/storage\s+.*-delete\b""") to "find /storage -delete",

        // Bricking / bootloader
        Regex("""\bfastboot\s+(erase|format|flash\s+boot|flash\s+system|flash\s+recovery)""") to "fastboot destructive operation",
        Regex("""\badb\s+(reboot\s+(bootloader|recovery)|shell\s+reboot)""") to "adb reboot to bootloader/recovery",

        // Privilege escalation / security disable
        Regex("""\bsu\s+-c\s+rm\b""") to "su -c rm",
        Regex("""\bsudo\s+rm\b""") to "sudo rm",
        Regex("""\bchmod\s+777\s+/(system|sdcard|storage|data|cache)(\s|$)""") to "chmod 777 on system path",
        Regex("""\bmount\s+-o\s+\S*rw\S*\s+/(system|sdcard|data)""") to "remount system path read-write",
        Regex("""\bsetenforce\s+0\b""") to "disable SELinux",
        Regex("""\bstop\s+(zygote|servicemanager|surfaceflinger)""") to "stop critical Android service",

        // Covert exfiltration / remote execution
        Regex("""\b(curl|wget)\s+[^|]*\|\s*(sh|bash|zsh|dash)\b""") to "remote script execution (curl|sh)",
        Regex("""\b(bash|sh|zsh|dash)\s+<\(\s*(curl|wget)""") to "bash <(curl/wget) process substitution",
        Regex("""\becho\s+[^|]+\|\s*base64\s+-d\s*\|\s*(sh|bash)""") to "base64-decoded payload piped to shell",
        Regex("""\becho\s+[^|]+\|\s*(sh|bash|zsh)""") to "echo piped to shell",

        // Fork bomb (canonical + whitespace variants)
        Regex("""\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""") to "fork bomb",

        // Wipe internal storage (alternate commands)
        Regex("""\b(format|wipe)\s+/(sdcard|data|cache)""") to "wipe command",
        Regex("""\brecovery\s+--wipe_data""") to "recovery wipe data",

        // Credential / key theft
        Regex("""\bcat\s+/(data|system)/.*/(passwords|keys|wallet|keystore)""") to "credential file access",
        Regex("""\bscp\s+.*\.keystore\b""") to "keystore exfiltration via scp",
        Regex("""\bcat\s+.*/whatsapp/messages\.db""") to "WhatsApp database read",

        // Cryptocurrency / wallet
        Regex("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*)?\s+.*/\.bitcoin/.*wallet""") to "bitcoin wallet deletion",
        Regex("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*)?\s+.*/\.eth/.*keystore""") to "eth keystore deletion",

        // System property tampering that disables security
        Regex("""\bsetprop\s+(ro\.debuggable|ro\.secure|ro\.build\.type)\s+""") to "security-related setprop"
    )

    // ── Hard blocked — literal substrings (post-normalization) ───────────
    private val HARD_BLOCKED_LITERALS = listOf(
        ":(){ :|:& };:",
        "rm -rf /*",
        "rm -rf /system",
        "rm -rf /data",
        "rm -rf /cache"
    )

    // ── Requires explicit user confirmation ──────────────────────────────
    private val NEEDS_CONFIRMATION_REGEX: List<Pair<Regex, String>> = listOf(
        Regex("""\breboot\b""") to "reboot",
        Regex("""\bshutdown\b""") to "shutdown",
        Regex("""\bpoweroff\b""") to "poweroff",
        Regex("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*|--recursive\s+--force)\s+\S""") to "recursive force delete",
        Regex("""\brm\s+-r\s+\S""") to "recursive delete",
        Regex("""\bgit\s+push\s+--force""") to "git push --force",
        Regex("""\bgit\s+reset\s+--hard""") to "git reset --hard",
        Regex("""\bDROP\s+TABLE\b""") to "SQL DROP TABLE",
        Regex("""\bDROP\s+DATABASE\b""") to "SQL DROP DATABASE",
        Regex("""\badb\s+shell\s+pm\s+uninstall""") to "uninstall app via adb",
        Regex("""\bam\s+force-stop""") to "force-stop app",
        Regex("""\bpkg\s+uninstall""") to "pkg uninstall",
        Regex("""\bapt(-get)?\s+remove""") to "apt remove",
        Regex("""\bcurl\s+-X\s+DELETE""") to "HTTP DELETE",
        Regex("""\bdel\s+/f\s+/s\s+/q""") to "Windows force-delete"
    )

    // ── Privacy-sensitive (log but allow with notice) ────────────────────
    private val PRIVACY_SENSITIVE_REGEX: List<Pair<Regex, String>> = listOf(
        Regex("""\bdumpsys\b""") to "dumpsys (system dump)",
        Regex("""\blogcat\b""") to "logcat (system logs)",
        Regex("""\bcat\s+/proc/""") to "read /proc",
        Regex("""\bcat\s+/etc/(passwd|shadow|hosts)""") to "read sensitive /etc file",
        Regex("""\bgetprop\s+ro\.serialno""") to "read device serial number",
        Regex("""\bsettings\s+get\b""") to "read system settings",
        Regex("""\bcontent\s+query\b""") to "content provider query",
        Regex("""\bpm\s+list\s+packages""") to "list installed packages",
        Regex("""\bifconfig\b""") to "ifconfig (network info)",
        Regex("""\bnetstat\b""") to "netstat (network info)",
        Regex("""\bip\s+addr\b""") to "ip addr (network info)"
    )

    private fun normalize(command: String): String {
        var c = command
        c = c.replace(Regex("""\$\{?IFS:?(\+\s)?\}?"""), " ")
        c = c.replace(Regex("""['"]"""), "")
        c = c.replace(Regex("""\s+"""), " ")
        c = c.replace(Regex("""(?<!:)//+"""), "/")
        c = decodeBase64Payloads(c)
        return c.trim().lowercase()
    }

    private fun decodeBase64Payloads(c: String): String {
        val pattern = Regex("""echo\s+([A-Za-z0-9+/=]{8,})\s*\|\s*base64\s+-d""")
        var result = c
        pattern.findAll(c).forEach { match ->
            val encoded = match.groupValues[1]
            val decoded = try {
                java.util.Base64.getDecoder().decode(encoded)
                    .decodeToString()
                    .replace(Regex("""[\x00-\x1F]"""), " ")
            } catch (_: Exception) { return@forEach }
            result = result.replace(encoded, decoded)
        }
        return result
    }

    fun isSafe(command: String): Boolean =
        validate(command).result != ValidationResult.BLOCKED

    fun safeVerdict(command: String): Verdict {
        val report = validate(command)
        return Verdict(
            safe = report.result != ValidationResult.BLOCKED,
            reason = report.reason
        )
    }

    fun verdict(command: String): Verdict = safeVerdict(command)

    fun validate(command: String): Report {
        val normalized = normalize(command)

        HARD_BLOCKED_LITERALS.firstOrNull { normalized.contains(it.lowercase()) }
            ?.let { return Report(ValidationResult.BLOCKED, "Destructive command blocked: $it", it) }

        for ((pattern, label) in HARD_BLOCKED_REGEX) {
            if (pattern.containsMatchIn(normalized)) {
                return Report(ValidationResult.BLOCKED, "Blocked: $label", pattern.pattern)
            }
        }

        for ((pattern, label) in PRIVACY_SENSITIVE_REGEX) {
            if (pattern.containsMatchIn(normalized)) {
                return Report(
                    ValidationResult.REQUIRES_CONFIRMATION,
                    "Privacy-sensitive: $label — accesses system data",
                    pattern.pattern
                )
            }
        }

        for ((pattern, label) in NEEDS_CONFIRMATION_REGEX) {
            if (pattern.containsMatchIn(normalized)) {
                return Report(
                    ValidationResult.REQUIRES_CONFIRMATION,
                    "High-risk: $label — requires your confirmation",
                    pattern.pattern
                )
            }
        }

        return Report(ValidationResult.SAFE, "Safe to execute")
    }

    fun validateAll(commands: List<ShellCommand>): List<Report> {
        val reports = mutableListOf<Report>()
        var blocked = false
        for (cmd in commands) {
            if (blocked) {
                reports.add(Report(ValidationResult.SAFE, "Skipped — earlier command was blocked"))
                continue
            }
            val r = validate(cmd.command)
            reports.add(r)
            if (r.result == ValidationResult.BLOCKED) blocked = true
        }
        return reports
    }

    fun hasBlocked(reports: List<Report>): Boolean =
        reports.any { it.result == ValidationResult.BLOCKED }

    fun hasConfirmRequired(reports: List<Report>): Boolean =
        reports.any { it.result == ValidationResult.REQUIRES_CONFIRMATION }
}
