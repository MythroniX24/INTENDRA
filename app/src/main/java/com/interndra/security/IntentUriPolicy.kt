package com.interndra.security

import java.net.URI

/** Policy for intents dispatched without an interactive confirmation dialog. */
object IntentUriPolicy {
    private val allowedSchemes = setOf(
        "http", "https", "tel", "smsto", "sms", "geo", "mailto"
    )

    fun isAllowed(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false

        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in allowedSchemes) return false

        // Web intents must identify a real host; accepting `https:` or an
        // empty-host URL would make malformed background intents retryable.
        return if (scheme == "http" || scheme == "https") {
            !uri.host.isNullOrBlank()
        } else {
            true
        }
    }
}
