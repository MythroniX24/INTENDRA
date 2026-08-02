package com.interndra.security

/**
 * Utilities for values crossing the autonomous plugin-to-shell boundary.
 *
 * Structured plugin fields are quoted before interpolation. Raw shell-fragment
 * fields (for example shell:exec or git:args) are validated separately by
 * PluginExecutionGuard and intentionally retain shell syntax such as pipes.
 */
object ShellArgumentPolicy {
    private fun hasControlCharacters(value: String): Boolean =
        value.any { it.code < 0x20 || it.code == 0x7F }

    /** Return the first argument that cannot safely cross a shell boundary. */
    fun firstUnsafeValue(args: Map<String, String>): String? =
        args.entries.firstOrNull { (_, value) -> hasControlCharacters(value) }?.key

    fun containsUnsafeSyntax(value: String): Boolean =
        hasControlCharacters(value)

    /** Shell syntax is only forbidden for fields that intentionally accept raw shell fragments. */
    fun containsShellSyntax(value: String): Boolean =
        value.any { it == ';' || it == '|' || it == '&' || it == '`' || it == '$' || it == '<' || it == '>' } ||
            value.contains("$(") || value.contains(")") || value.any { it == '\r' || it == '\n' }

    /** POSIX shell single-quote escaping for one structured argument. */
    fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    /**
     * Replace a template placeholder without double-quoting it when the
     * template author already wrapped it in single or double quotes.
     */
    fun replaceTemplateValue(template: String, key: String, value: String): String {
        val quoted = shellQuote(value)
        return template
            .replace("\"{$key}\"", quoted)
            .replace("'{$key}'", quoted)
            .replace("{$key}", quoted)
    }
}
