package com.interndra.security

/**
 * Redacts credential-like values from logs, exports, and diagnostic text.
 * This is deliberately conservative: false positives are safer than leaking
 * a token into Logcat or an exported file.
 */
object SensitiveDataRedactor {
    private val labeledSecret = Regex(
        // "authorization"/"bearer" labels are deliberately excluded here: a single-word
        // value pattern could only ever consume "Bearer" from "Authorization: Bearer <token>",
        // leaving the real token exposed. The dedicated bearer regex below handles that form.
        "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|otp|pin)(\\s*[:=]\\s*)([^\\s,;]+)"
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val openAiLike = Regex("\\b(?:sk|sess)[-_][A-Za-z0-9_-]{12,}\\b")
    private val googleLike = Regex("\\bAIza[A-Za-z0-9_-]{20,}\\b")
    private val githubLike = Regex("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b")
    private val longHex = Regex("\\b[0-9a-fA-F]{40,}\\b")

    fun redact(value: String): String {
        if (value.isEmpty()) return value
        return value
            // Bearer first: "Authorization: Bearer <token>" needs the dedicated
            // two-word pattern, otherwise only "Bearer" would be consumed.
            .replace(bearer, "Bearer ***REDACTED***")
            .replace(labeledSecret) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}***REDACTED***"
            }
            .replace(openAiLike, "***REDACTED_TOKEN***")
            .replace(googleLike, "***REDACTED_TOKEN***")
            .replace(githubLike, "***REDACTED_TOKEN***")
            .replace(longHex, "***REDACTED_TOKEN***")
    }
}
