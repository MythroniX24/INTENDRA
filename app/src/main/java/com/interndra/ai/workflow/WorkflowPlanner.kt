package com.interndra.ai.workflow

import com.interndra.data.model.*
import java.io.File

/**
 * WorkflowPlanner — Phase 5/6/7 intent detection and workflow composition.
 *
 * Turns natural-language user requests into structured [Workflow] objects
 * that the WorkflowEngine can execute safely.
 *
 * Capabilities:
 *  - WhatsApp messaging workflows (open app, pre-fill message, optional contact)
 *  - File search workflows (find PDFs, images, downloads, APKs, project files
 *    by extension, name pattern, or date range)
 *  - File operations (list, count, size, open, share)
 *  - App launch workflows
 *  - Communication workflows (call, dial, SMS, email)
 *  - Device info workflows (battery, storage, network)
 *  - Multi-step chained workflows (e.g. "find PDFs and share the first one")
 *
 * The planner is intentionally conservative: if it can't confidently map a
 * request to a known workflow, it returns null and the caller falls back to
 * the AI orchestrator. This prevents misfires on ambiguous commands.
 */
class WorkflowPlanner {

    /** Intent categories the planner can detect. */
    enum class Intent {
        WHATSAPP_MESSAGE,
        WHATSAPP_CONTACT,
        FIND_FILES,
        LIST_FILES,
        OPEN_FILE,
        SHARE_FILE,
        LAUNCH_APP,
        CALL_PHONE,
        SEND_SMS,
        DEVICE_INFO,
        UNKNOWN
    }

    data class DetectedIntent(
        val intent: Intent,
        val confidence: Float,
        val packageName: String? = null,
        val contactHint: String? = null,
        val message: String? = null,
        val fileExtensions: List<String> = emptyList(),
        val directory: String? = null,
        val namePattern: String? = null,
        val phone: String? = null,
        val smsBody: String? = null,
        val infoType: String? = null,
        val rawInput: String
    )

    fun detect(input: String): DetectedIntent? {
        val lower = input.lowercase().trim()
        if (lower.isBlank()) return null

        val candidates = mutableListOf<DetectedIntent>()
        detectWhatsApp(lower, input)?.let { candidates.add(it) }
        detectFileSearch(lower, input)?.let { candidates.add(it) }
        detectListFiles(lower, input)?.let { candidates.add(it) }
        detectOpenFile(lower, input)?.let { candidates.add(it) }
        detectShareFile(lower, input)?.let { candidates.add(it) }
        detectLaunchApp(lower, input)?.let { candidates.add(it) }
        detectCall(lower, input)?.let { candidates.add(it) }
        detectSms(lower, input)?.let { candidates.add(it) }
        detectDeviceInfo(lower, input)?.let { candidates.add(it) }

        return candidates.maxByOrNull { it.confidence }?.takeIf { it.confidence >= 0.4f }
    }

    fun plan(detected: DetectedIntent): Workflow? = when (detected.intent) {
        Intent.WHATSAPP_MESSAGE, Intent.WHATSAPP_CONTACT -> planWhatsApp(detected)
        Intent.FIND_FILES       -> planFindFiles(detected)
        Intent.LIST_FILES       -> planListFiles(detected)
        Intent.OPEN_FILE        -> planOpenFile(detected)
        Intent.SHARE_FILE       -> planShareFile(detected)
        Intent.LAUNCH_APP       -> planLaunchApp(detected)
        Intent.CALL_PHONE       -> planCall(detected)
        Intent.SEND_SMS         -> planSms(detected)
        Intent.DEVICE_INFO      -> planDeviceInfo(detected)
        Intent.UNKNOWN          -> null
    }

    // ── Detection rules ──────────────────────────────────────────────────

    private fun detectWhatsApp(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("whatsapp")) return null
        val msg = extractMessageText(raw)
        val contact = extractContact(raw)
        val intent = if (contact != null) Intent.WHATSAPP_CONTACT else Intent.WHATSAPP_MESSAGE
        return DetectedIntent(
            intent = intent,
            confidence = 0.9f,
            packageName = "com.whatsapp",
            contactHint = contact,
            message = msg,
            rawInput = raw
        )
    }

    private fun detectFileSearch(lower: String, raw: String): DetectedIntent? {
        val findKeywords = listOf("find", "search for", "locate", "show me", "list all")
        val matches = findKeywords.any { lower.contains(it) }
        if (!matches) return null

        val extensions = mutableListOf<String>()
        if (lower.contains("pdf")) extensions += "pdf"
        if (lower.contains("image") || lower.contains("photo") || lower.contains("picture"))
            extensions += listOf("jpg", "jpeg", "png", "gif", "webp")
        if (lower.contains("apk")) extensions += "apk"
        if (lower.contains("doc") || lower.contains("document"))
            extensions += listOf("doc", "docx", "txt", "pdf")
        if (lower.contains("audio") || lower.contains("music") || lower.contains("mp3"))
            extensions += listOf("mp3", "wav", "ogg", "m4a")
        if (lower.contains("video")) extensions += listOf("mp4", "mkv", "webm", "3gp")
        if (lower.contains("project"))
            extensions += listOf("kt", "java", "py", "js", "ts", "gradle", "xml")
        if (lower.contains("zip") || lower.contains("archive"))
            extensions += listOf("zip", "tar", "gz")

        if (extensions.isEmpty() && !lower.contains("file") && !lower.contains("files")) return null

        val directory = when {
            lower.contains("download") -> "/storage/emulated/0/Download"
            lower.contains("document") -> "/storage/emulated/0/Documents"
            lower.contains("picture") || lower.contains("photo") -> "/storage/emulated/0/Pictures"
            lower.contains("dcim") || lower.contains("camera") -> "/storage/emulated/0/DCIM"
            lower.contains("music") -> "/storage/emulated/0/Music"
            lower.contains("movie") || lower.contains("video") -> "/storage/emulated/0/Movies"
            else -> "/storage/emulated/0"
        }

        val namePattern = Regex(
            """(?:named|called|containing|matching)\s+["']?(\w[\w\s.-]*?)["']?(?:\s|$|,|and)"""
        ).find(lower)?.groupValues?.getOrNull(1)?.trim()

        return DetectedIntent(
            intent = Intent.FIND_FILES,
            confidence = 0.85f,
            fileExtensions = extensions.distinct(),
            directory = directory,
            namePattern = namePattern,
            rawInput = raw
        )
    }

    private fun detectListFiles(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("list") && !lower.contains("ls ") && !lower.contains("show files")) return null
        val directory = when {
            lower.contains("download") -> "/storage/emulated/0/Download"
            lower.contains("document") -> "/storage/emulated/0/Documents"
            lower.contains("picture") -> "/storage/emulated/0/Pictures"
            else -> null
        } ?: return null
        return DetectedIntent(
            intent = Intent.LIST_FILES,
            confidence = 0.7f,
            directory = directory,
            rawInput = raw
        )
    }

    private fun detectOpenFile(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("open") && !lower.contains("launch file")) return null
        val path = Regex("""(/storage/emulated/0/\S+|/sdcard/\S+|~/\S+)""").find(raw)?.value
            ?: return null
        return DetectedIntent(
            intent = Intent.OPEN_FILE,
            confidence = 0.8f,
            directory = path,
            rawInput = raw
        )
    }

    private fun detectShareFile(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("share")) return null
        val path = Regex("""(/storage/emulated/0/\S+|/sdcard/\S+|~/\S+)""").find(raw)?.value
            ?: return null
        return DetectedIntent(
            intent = Intent.SHARE_FILE,
            confidence = 0.8f,
            directory = path,
            rawInput = raw
        )
    }

    private fun detectLaunchApp(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("open") && !lower.contains("launch") && !lower.contains("start ")) return null
        val appMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "settings" to "com.android.settings",
            "calculator" to "com.android.calculator2",
            "calendar" to "com.google.android.calendar",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "termux" to "com.termux"
        )
        for ((name, pkg) in appMap) {
            if (lower.contains("open $name") || lower.contains("launch $name") ||
                lower.contains("start $name") || lower == "open $name") {
                return DetectedIntent(
                    intent = Intent.LAUNCH_APP,
                    confidence = 0.85f,
                    packageName = pkg,
                    rawInput = raw
                )
            }
        }
        val pkgMatch = Regex("""\b(com\.[a-z]+\.[a-z]+)\b""").find(lower)
        if (pkgMatch != null && (lower.contains("open") || lower.contains("launch"))) {
            return DetectedIntent(
                intent = Intent.LAUNCH_APP,
                confidence = 0.7f,
                packageName = pkgMatch.value,
                rawInput = raw
            )
        }
        return null
    }

    private fun detectCall(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("call") && !lower.contains("dial")) return null
        val phone = Regex("""\+?\d[\d\s\-]{7,}\d""").find(raw)?.value?.replace(Regex("""[\s\-]"""), "")
            ?: return null
        val direct = lower.startsWith("call ") || lower.contains("call +")
        return DetectedIntent(
            intent = Intent.CALL_PHONE,
            confidence = 0.85f,
            phone = phone,
            rawInput = raw,
            infoType = if (direct) "direct" else "dial"
        )
    }

    private fun detectSms(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("sms") && !lower.contains("text message") && !lower.contains("send message to")) return null
        val phone = Regex("""\+?\d[\d\s\-]{7,}\d""").find(raw)?.value?.replace(Regex("""[\s\-]"""), "")
            ?: return null
        val body = extractMessageText(raw)
        return DetectedIntent(
            intent = Intent.SEND_SMS,
            confidence = 0.8f,
            phone = phone,
            smsBody = body,
            rawInput = raw
        )
    }

    private fun detectDeviceInfo(lower: String, raw: String): DetectedIntent? {
        val infoType = when {
            lower.contains("battery") -> "battery"
            lower.contains("storage") || lower.contains("space") -> "storage"
            lower.contains("network") || lower.contains("wifi") || lower.contains("internet") -> "network"
            lower.contains("device info") || lower.contains("system info") -> "all"
            else -> return null
        }
        return DetectedIntent(
            intent = Intent.DEVICE_INFO,
            confidence = 0.75f,
            infoType = infoType,
            rawInput = raw
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun extractMessageText(raw: String): String? {
        val patterns = listOf(
            Regex("""(?:saying|message:|text:|with message:?)\s+["'](.+?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:saying|message:|text:|with message:?)\s+(.+?)(?:\s+to\s+|\s*$)""", RegexOption.IGNORE_CASE),
            Regex("""["'](.+?)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1) return match
        }
        return null
    }

    private fun extractContact(raw: String): String? {
        val patterns = listOf(
            Regex("""(?:to|for)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""),
            Regex("""(?:message|text|send)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""")
        )
        val reserved = listOf("whatsapp", "message", "text", "send")
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1 && match.lowercase() !in reserved) {
                return match
            }
        }
        return null
    }

    // ── Planners ─────────────────────────────────────────────────────────

    private fun planWhatsApp(d: DetectedIntent): Workflow {
        val msg = d.message ?: ""
        val contact = d.contactHint
        val cmdStr = if (contact != null) {
            "sendtext:com.whatsapp:${contact}:${msg.ifBlank { "(no message)" }}"
        } else {
            "sendtext:com.whatsapp:${msg.ifBlank { "(no message)" }}"
        }
        return Workflow(
            name = if (contact != null) "WhatsApp → $contact" else "WhatsApp message",
            description = "Open WhatsApp with your message pre-filled. " +
                          "You'll pick the contact and tap Send to deliver.",
            steps = listOf(
                WorkflowStep(
                    label = "Open WhatsApp with pre-filled message",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = cmdStr,
                        description = "Open WhatsApp with pre-filled message",
                        critical = true
                    )
                )
            ),
            tags = listOf("messaging", "whatsapp")
        )
    }

    private fun planFindFiles(d: DetectedIntent): Workflow {
        val dir = d.directory ?: "/storage/emulated/0"
        val extensions = d.fileExtensions
        val namePattern = d.namePattern

        val extArgs = if (extensions.isEmpty() || extensions.any { it.isEmpty() }) {
            ""
        } else {
            extensions.joinToString(" ", prefix = "\\( ") { "-iname \\*.$it" } + " \\)"
        }
        val nameArg = if (!namePattern.isNullOrBlank()) "-iname \\*$namePattern*" else ""
        val typeArg = if (extArgs.isEmpty() && nameArg.isEmpty()) "-type f" else "-type f"

        val cmd = buildString {
            append("find \"")
            append(dir)
            append("\" ")
            append(typeArg)
            if (nameArg.isNotEmpty()) { append(" "); append(nameArg) }
            if (extArgs.isNotEmpty()) { append(" "); append(extArgs) }
            append(" 2>/dev/null | head -n 100")
        }

        return Workflow(
            name = "Find files",
            description = "Search for files in ${dir.replace("/storage/emulated/0", "~")}" +
                          (if (extensions.isNotEmpty()) " matching extensions: ${extensions.joinToString(", ")}" else "") +
                          (if (!namePattern.isNullOrBlank()) " named '$namePattern'" else ""),
            steps = listOf(
                WorkflowStep(
                    label = "Search storage for matching files",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = cmd,
                        description = "Find files matching criteria",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "search")
        )
    }

    private fun planListFiles(d: DetectedIntent): Workflow {
        val dir = d.directory ?: "/storage/emulated/0"
        return Workflow(
            name = "List files",
            description = "List files in ${dir.replace("/storage/emulated/0", "~")}",
            steps = listOf(
                WorkflowStep(
                    label = "List directory contents",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "ls -lah \"$dir\" 2>/dev/null",
                        description = "List files in directory",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "list")
        )
    }

    private fun planOpenFile(d: DetectedIntent): Workflow {
        val path = d.directory ?: return planFindFiles(d)
        return Workflow(
            name = "Open file",
            description = "Open ${File(path).name}",
            steps = listOf(
                WorkflowStep(
                    label = "Open file via system viewer",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "openfile:$path",
                        description = "Open file with appropriate app",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "open")
        )
    }

    private fun planShareFile(d: DetectedIntent): Workflow {
        val path = d.directory ?: return planFindFiles(d)
        return Workflow(
            name = "Share file",
            description = "Share ${File(path).name} via system share sheet",
            steps = listOf(
                WorkflowStep(
                    label = "Open share sheet",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "sharefile:$path",
                        description = "Share file via system share sheet",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "share")
        )
    }

    private fun planLaunchApp(d: DetectedIntent): Workflow {
        val pkg = d.packageName ?: return planFindFiles(d)
        return Workflow(
            name = "Launch app",
            description = "Open $pkg",
            steps = listOf(
                WorkflowStep(
                    label = "Launch $pkg",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "open:$pkg",
                        description = "Launch app",
                        critical = true
                    )
                )
            ),
            tags = listOf("app", "launch")
        )
    }

    private fun planCall(d: DetectedIntent): Workflow {
        val phone = d.phone ?: return planFindFiles(d)
        val direct = d.infoType == "direct"
        return Workflow(
            name = if (direct) "Call $phone" else "Dial $phone",
            description = if (direct) "Initiate a call to $phone" else "Open dialer with $phone",
            steps = listOf(
                WorkflowStep(
                    label = if (direct) "Call $phone" else "Dial $phone",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = if (direct) "call:$phone" else "dial:$phone",
                        description = if (direct) "Initiate call" else "Open dialer",
                        critical = true
                    )
                )
            ),
            tags = listOf("phone", if (direct) "call" else "dial")
        )
    }

    private fun planSms(d: DetectedIntent): Workflow {
        val phone = d.phone ?: return planFindFiles(d)
        val body = d.smsBody ?: ""
        return Workflow(
            name = "SMS to $phone",
            description = "Open SMS app with message pre-filled",
            steps = listOf(
                WorkflowStep(
                    label = "Open SMS app",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "sms:$phone:$body",
                        description = "Open SMS with pre-filled body",
                        critical = true
                    )
                )
            ),
            tags = listOf("messaging", "sms")
        )
    }

    private fun planDeviceInfo(d: DetectedIntent): Workflow {
        val infoType = d.infoType ?: "all"
        val cmd = when (infoType) {
            "battery" -> "dumpsys battery | grep -E 'level|status|temperature|voltage' 2>/dev/null"
            "storage" -> "df -h /storage/emulated/0 2>/dev/null; echo '---'; du -sh /storage/emulated/0/* 2>/dev/null | head -20"
            "network" -> "dumpsys wifi | grep -E 'SSID|state|RSSI' 2>/dev/null; echo '---'; ip addr show wlan0 2>/dev/null | grep inet"
            else -> "dumpsys battery | grep -E 'level|status' 2>/dev/null; echo '---'; df -h /storage/emulated/0 2>/dev/null; echo '---'; dumpsys wifi | grep -E 'SSID|state' 2>/dev/null"
        }
        return Workflow(
            name = "Device info: $infoType",
            description = "Retrieve $infoType information from the device",
            steps = listOf(
                WorkflowStep(
                    label = "Query device $infoType",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = cmd,
                        description = "Get $infoType info",
                        critical = true
                    )
                )
            ),
            tags = listOf("device", "info", infoType)
        )
    }
}
