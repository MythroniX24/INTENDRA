package com.interndra.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * ContentExtractor — extracts the main readable article content from raw HTML.
 *
 * Removes navigation, ads, cookie banners, social widgets and other chrome;
 * then pulls paragraphs/headings/list items from the primary content region.
 */
object ContentExtractor {

    private const val MAX_PAGE_CHARS = 2_500
    private val REMOVE_SELECTORS = listOf(
        "script", "style", "noscript", "iframe", "form", "svg", "canvas",
        "nav", "header", "footer", "aside",
        "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
        ".ad", ".ads", ".advert", ".advertisement", ".sponsored", ".sponsor",
        ".nav", ".navbar", ".menu", ".sidebar", ".footer", ".header", ".top-bar",
        ".related", ".recommend", ".recommended", ".comments", ".comment-section",
        ".comment-list", ".social", ".share", ".share-buttons", ".newsletter",
        ".subscribe", ".popup", ".modal", ".overlay", ".cookie", ".cookie-banner",
        ".cookie-consent", ".gdpr", ".consent", ".banner", ".promo", ".promotion",
        ".widget", ".ad-container", ".ad-banner", "[id*=cookie]", "[id*=advert]",
        "[class*=cookie]", "[class*=advert]", "[class*=popup]", "[id*=popup]"
    )

    private val CONTENT_SELECTORS = listOf(
        "article", "main", "[role=main]", ".article-body", ".post-content",
        ".entry-content", ".content-body", ".story-body", ".article-content",
        ".post-body", ".page-content", ".entry", ".content", "#content", ".main-content"
    )

    /**
     * Extract the main readable text of an HTML document.
     *
     * @return cleaned plain-text content, or the fallback title if extraction
     *         yields too little.
     */
    fun extractMainContent(html: String, fallbackTitle: String): String {
        if (html.isBlank()) return ""

        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return fallbackTitle

        // Remove all chrome
        REMOVE_SELECTORS.forEach { sel ->
            runCatching { doc.select(sel).remove() }
        }

        val contentRoot = CONTENT_SELECTORS.asSequence()
            .mapNotNull { sel -> runCatching { doc.selectFirst(sel) }.getOrNull() }
            .maxByOrNull { el -> textDensity(el) }
            ?: doc

        val paragraphs = contentRoot.select("p, h1, h2, h3, h4, li, pre, blockquote")
        if (paragraphs.isEmpty()) return fallbackTitle

        val sb = StringBuilder()
        for (el in paragraphs) {
            val text = el.text().trim()
            // Headings are often short (e.g. "## Overview") — use a lower
            // threshold for them; body text needs to be substantial.
            val isHeading = el.tagName() in setOf("h1", "h2", "h3", "h4")
            if (isHeading) {
                if (text.length < 2) continue
            } else if (text.length < 20) {
                continue
            }
            when (el.tagName()) {
                "h1" -> sb.append("## ").appendLine(text)
                "h2" -> sb.append("### ").appendLine(text)
                "h3", "h4" -> sb.append("#### ").appendLine(text)
                "li" -> sb.append("• ").appendLine(text)
                "pre" -> {
                    sb.appendLine("```")
                    sb.appendLine(text.take(400))
                    sb.appendLine("```")
                }
                else -> sb.appendLine(text)
            }
            sb.appendLine()
            if (sb.length > MAX_PAGE_CHARS * 2) break
        }

        val result = sb.toString().trim()
        return if (result.length > 50) result else fallbackTitle
    }

    /** Extract the page <title> for display. */
    fun extractTitle(html: String): String {
        if (html.isBlank()) return ""
        return runCatching {
            Jsoup.parse(html).title()?.trim() ?: ""
        }.getOrDefault("")
    }

    private fun textDensity(el: Element): Int {
        return try {
            el.text().length - el.select("script,style,nav,footer,aside").text().length
        } catch (_: Exception) {
            el.text().length
        }
    }
}
