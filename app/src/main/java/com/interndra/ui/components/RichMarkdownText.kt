@file:OptIn(ExperimentalLayoutApi::class)
package com.interndra.ui.components

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.interndra.ui.theme.Accent
import com.interndra.ui.theme.Background800
import com.interndra.ui.theme.Danger
import com.interndra.ui.theme.SurfaceLight
import com.interndra.ui.theme.Success
import com.interndra.ui.theme.TerminalWhite
import com.interndra.ui.theme.TerminalYellow
import com.interndra.ui.theme.VaultCyan
import com.interndra.ui.theme.VaultPurple
import com.interndra.util.ImageCacheUtil

/**
 * RichMarkdownText — ENHANCED premium AI chat renderer (ChatGPT-level).
 * Supports: headings, bullet/numbered/checklists, code+diff blocks, callouts,
 * tables, math (LaTeX), collapsible sections, spoilers, footnotes, citations,
 * mermaid diagrams, file trees, definition lists, tag chips, keyboard shortcuts,
 * image placeholders, blockquote variants, horizontal rules.
 */
@Composable
fun RichMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    isStreaming: Boolean = false
) {
    val blocks = remember(markdown, isStreaming) { EnhancedMarkdownParser.parse(markdown, isStreaming) }
    val clipboardManager = LocalClipboardManager.current
    var copyClicked by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Copy entire response button (shown on hover/long press area)
        Box(Modifier.fillMaxWidth()) {
            // SelectionContainer lets users long-press to select/copy AI text
            // (links still open on tap via ClickableText).
            SelectionContainer {
                Column {
                blocks.forEach { block ->
                    when (block) {
                        is EnhancedBlock.Heading -> HeadingBlock(block)
                        is EnhancedBlock.Paragraph -> ParagraphBlock(block, onLinkClick)
                        is EnhancedBlock.BulletList -> BulletListBlock(block, onLinkClick)
                        is EnhancedBlock.NumberedList -> NumberedListBlock(block, onLinkClick)
                        is EnhancedBlock.Checklist -> ChecklistBlock(block, onLinkClick)
                        is EnhancedBlock.CodeBlock -> CodeBlockBlock(block)
                        is EnhancedBlock.DiffBlock -> DiffBlockBlock(block)
                        is EnhancedBlock.Quote -> QuoteBlock(block, onLinkClick)
                        is EnhancedBlock.Table -> TableBlock(block)
                        is EnhancedBlock.Callout -> CalloutBlock(block, onLinkClick)
                        is EnhancedBlock.MathBlock -> MathBlock(block)
                        is EnhancedBlock.Collapsible -> CollapsibleBlock(block, onLinkClick)
                        is EnhancedBlock.DefinitionList -> DefinitionListBlock(block, onLinkClick)
                        is EnhancedBlock.FootnoteSection -> FootnoteSectionBlock(block, onLinkClick)
                        is EnhancedBlock.Mermaid -> MermaidBlock(block)
                        is EnhancedBlock.FileTree -> FileTreeBlock(block)
                        is EnhancedBlock.HorizontalRule -> HorizontalRuleBlock()
                        is EnhancedBlock.TagList -> TagListBlock(block)
                        is EnhancedBlock.Spoiler -> SpoilerBlock(block, onLinkClick)
                        is EnhancedBlock.ImagePlaceholder -> ImagePlaceholderBlock(block)
                        is EnhancedBlock.FootnoteRef, is EnhancedBlock.KeyboardShortcut -> {} // handled inline
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Copy entire response footer
                if (blocks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            modifier = Modifier.clickable {
                                clipboardManager.setText(AnnotatedString(markdown))
                                copyClicked = true
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy, "Copy response",
                                    tint = if (copyClicked) Success else TerminalWhite.copy(alpha = 0.2f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    if (copyClicked) "Copied!" else "Copy response",
                                    color = if (copyClicked) Success else TerminalWhite.copy(alpha = 0.2f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

// ── Block types ────────────────────────────────────────────────────────────

sealed class EnhancedBlock {
    data class Heading(val level: Int, val text: String, val style: HeadingStyle = HeadingStyle.NORMAL) : EnhancedBlock()
    data class Paragraph(val text: String) : EnhancedBlock()
    data class BulletList(val items: List<String>, val indentLevels: List<Int>, val tight: Boolean = false) : EnhancedBlock()
    data class NumberedList(val items: List<String>, val numbers: List<Int>) : EnhancedBlock()
    data class Checklist(val items: List<Pair<Boolean, String>>, val header: String? = null) : EnhancedBlock()
    data class CodeBlock(val language: String, val code: String, val showLines: Boolean = true, val highlightLines: List<Int> = emptyList()) : EnhancedBlock()
    data class DiffBlock(val language: String, val lines: List<DiffLine>) : EnhancedBlock()
    data class Quote(val lines: List<String>, val quoteType: QuoteType = QuoteType.NORMAL) : EnhancedBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val alignment: List<TextAlign?> = emptyList()) : EnhancedBlock()
    data class Callout(val type: CalloutType, val text: String, val title: String? = null) : EnhancedBlock()
    data class MathBlock(val formula: String, val display: Boolean = true) : EnhancedBlock()
    data class Collapsible(val summary: String, val content: String, val open: Boolean = false) : EnhancedBlock()
    data class DefinitionList(val terms: List<Pair<String, String>>) : EnhancedBlock()
    data class FootnoteRef(val id: String, val text: String) : EnhancedBlock()
    data class FootnoteSection(val footnotes: List<Pair<String, String>>) : EnhancedBlock()
    data class Mermaid(val code: String) : EnhancedBlock()
    data class FileTree(val tree: String) : EnhancedBlock()
    data class TagList(val tags: List<Pair<String, String>>) : EnhancedBlock()
    data class Spoiler(val text: String) : EnhancedBlock()
    data class KeyboardShortcut(val keys: List<String>) : EnhancedBlock()
    data class ImagePlaceholder(val alt: String, val url: String? = null) : EnhancedBlock()
    object HorizontalRule : EnhancedBlock()
}

data class DiffLine(val type: DiffType, val text: String)
enum class DiffType { ADDED, REMOVED, CONTEXT, HEADER }
enum class HeadingStyle { NORMAL, CENTERED, WITH_LINE }
enum class QuoteType { NORMAL, TWEET, GITHUB_ALERT }
enum class CalloutType { INFO, SUCCESS, WARNING, DANGER, TIP, QUESTION, FIRE }

// ── Parser (ChatGPT-level) ────────────────────────────────────────────────

/** Compiled once at class-load: the parser evaluates several of these against
 *  every line, so constructing them per line would dominate the cost of long
 *  (10k+ char) responses. Hoisting keeps a full re-parse on each streaming
 *  commit cheap. */
private val P_HR_RE = Regex("""^\s*([-*_])\1{2,}\s*$""")
private val P_DETAILS_RE = Regex("""^<details>\s*(open)?\s*$""", RegexOption.IGNORE_CASE)
private val P_SUMMARY_RE = Regex("""^\s*<summary>\s*(.+?)\s*</summary>\s*$""", RegexOption.IGNORE_CASE)
private val P_HEADING_RE = Regex("""^(#{1,6})\s+(.+?)(?:\s*\{#\w+\})?\s*$""")
private val P_DEFLIST_RE = Regex("""^\s*.+\s*::\s*.+$""")
private val P_CHECK_ITEM_RE = Regex("""^\s*[-*]\s+\[[ xX]\]\s+.+""")
private val P_CHECK_PARSE_RE = Regex("""^\s*[-*]\s+\[([ xX])\]\s+(.+)$""")
private val P_TAG_RE = Regex("""^:(\w[\w-]*):\s*(.+)$""")
private val P_TAG_START_RE = Regex("""^:\w[\w-]*:\s+""")
private val P_BULLET_RE = Regex("""^\s*[-*+]\s+.+""")
private val P_BULLET_NO_CHECK_RE = Regex("""^\s*[-*+]\s+\[[ xX]\]""")
private val P_BULLET_PARSE_RE = Regex("""^(\s*)[-*+]\s+(.+)$""")
private val P_BULLET_BREAK_RE = Regex("""^\s*[-*+]\s+""")
private val P_NUMBERED_RE = Regex("""^\s*\d+[.)]\s+.+""")
private val P_NUMBERED_PARSE_RE = Regex("""^\s*(\d+)[.)]\s+(.+)$""")
private val P_NUMBERED_BREAK_RE = Regex("""^\s*\d+[.)]\s+""")
private val P_FOOTNOTE_RE = Regex("""^\s*\[\^[\w-]+\]:\s+.+""")
private val P_FOOTNOTE_PARSE_RE = Regex("""^\s*\[\^([\w-]+)\]:\s+(.+)$""")
private val P_FOOTNOTE_BREAK_RE = Regex("""^\s*\[\^[\w-]+\]:\s+""")
private val P_TABLE_SEP_RE = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
private val P_TABLE_ROW_RE = Regex("""^\|.+?\|.*\|""")
private val P_SPOILER_RE = Regex("""^.*\|\|.+\|\|.*$""")

object EnhancedMarkdownParser {
    /**
     * Parse markdown into renderable blocks.
     *
     * @param isStreaming when true the text is a growing AI stream: the parser
     *   must be forgiving of incomplete constructs (an unclosed code fence,
     *   a just-opened fence line, half-typed tables) so they render
     *   progressively instead of deadlocking or flashing empty boxes.
     */
    fun parse(md: String, isStreaming: Boolean = false): List<EnhancedBlock> {
        val blocks = mutableListOf<EnhancedBlock>()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            // HR
            if (line.matches(P_HR_RE)) {
                blocks.add(EnhancedBlock.HorizontalRule); i++; continue
            }
            // Math display
            if (line.trimStart().startsWith("$$")) {
                if (line.trim().endsWith("$$") && line.trim().length > 4) {
                    blocks.add(EnhancedBlock.MathBlock(line.trim().removePrefix("$$").removeSuffix("$$").trim(), true)); i++; continue
                }
                val ml = mutableListOf<String>(); i++
                while (i < lines.size && !lines[i].trim().startsWith("$$")) { ml.add(lines[i]); i++ }
                i++; blocks.add(EnhancedBlock.MathBlock(ml.joinToString("\n"), true)); continue
            }
            // Code / diff / mermaid / tree
            if (line.trimStart().startsWith("```")) {
                val lang = line.trim().removePrefix("```").trim()
                val isMermaid = lang == "mermaid"
                val isTree = lang == "tree" || lang == "directory"
                val isDiff = lang == "diff" || lang.startsWith("diff-")
                val actualLang = if (isDiff) lang.removePrefix("diff-").removePrefix("diff").trim() else lang
                val cl = mutableListOf<String>(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { cl.add(lines[i]); i++ }
                i++
                // While streaming, a fence that was just opened (no language, no
                // content yet) renders as plain text so a thin empty code box
                // doesn't flash before the model types the language + code.
                if (isStreaming && cl.isEmpty() && lang.isBlank()) {
                    blocks.add(EnhancedBlock.Paragraph("```")); continue
                }
                when {
                    isMermaid -> blocks.add(EnhancedBlock.Mermaid(cl.joinToString("\n")))
                    isTree -> blocks.add(EnhancedBlock.FileTree(cl.joinToString("\n")))
                    isDiff || actualLang == "diff" -> blocks.add(EnhancedBlock.DiffBlock(actualLang, cl.map { l ->
                        when {
                            l.startsWith("+") && !l.startsWith("+++") -> DiffLine(DiffType.ADDED, l.removePrefix("+"))
                            l.startsWith("-") && !l.startsWith("---") -> DiffLine(DiffType.REMOVED, l.removePrefix("-"))
                            l.startsWith("@@") -> DiffLine(DiffType.HEADER, l)
                            else -> DiffLine(DiffType.CONTEXT, l)
                        }
                    }))
                    else -> blocks.add(EnhancedBlock.CodeBlock(actualLang, cl.joinToString("\n")))
                }; continue
            }
            // Collapsible
            val dm = P_DETAILS_RE.find(line.trim())
            if (dm != null) {
                val open = dm.groupValues[1].lowercase() == "open"
                var summary = ""; i++
                val sm = if (i < lines.size) P_SUMMARY_RE.find(lines[i]) else null
                if (sm != null) { summary = sm.groupValues[1]; i++ }
                val cl = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("</details>")) { cl.add(lines[i]); i++ }
                i++; blocks.add(EnhancedBlock.Collapsible(summary, cl.joinToString("\n"), open)); continue
            }
            // Heading
            val hm = P_HEADING_RE.find(line)
            if (hm != null) {
                val lvl = hm.groupValues[1].length; var t = hm.groupValues[2].trim()
                val s = when { t.startsWith("==") && t.endsWith("==") -> { t = t.drop(1).dropLast(1).trim(); HeadingStyle.CENTERED }
                    t.endsWith("---") || t.endsWith("___") -> { t = t.dropLast(3).trim(); HeadingStyle.WITH_LINE }
                    else -> HeadingStyle.NORMAL }
                blocks.add(EnhancedBlock.Heading(lvl, t, s)); i++; continue
            }
            // Definition list
            if (P_DEFLIST_RE.matches(line)) {
                val terms = mutableListOf<Pair<String, String>>()
                while (i < lines.size && P_DEFLIST_RE.matches(lines[i])) {
                    val p = lines[i].split("::", limit = 2); terms.add(p[0].trim() to p[1].trim()); i++ }
                blocks.add(EnhancedBlock.DefinitionList(terms)); continue
            }
            // Quote
            if (line.trimStart().startsWith(">")) {
                val ql = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { ql.add(lines[i].trimStart().removePrefix(">").trim()); i++ }
                val t = ql.joinToString("\n"); val first = ql.firstOrNull().orEmpty()
                val isGh = t.startsWith("[!NOTE]",true) || t.startsWith("[!WARNING]",true) || t.startsWith("[!TIP]",true) || t.startsWith("[!IMPORTANT]",true) || t.startsWith("[!CAUTION]",true)
                val isTw = first.startsWith("📱") || first.startsWith("💡")
                val calloutType = when {
                    first.contains("⚠") || first.startsWith("Warning:",true) || first.startsWith("[!WARNING]",true) -> CalloutType.WARNING
                    first.contains("✅") || first.startsWith("✓") || first.startsWith("Done:",true) || first.startsWith("[!TIP]",true) -> CalloutType.SUCCESS
                    first.contains("ℹ") || first.startsWith("Note:",true) || first.startsWith("[!NOTE]",true) -> CalloutType.INFO
                    first.contains("🔴") || first.startsWith("Danger:",true) || first.startsWith("[!CAUTION]",true) -> CalloutType.DANGER
                    first.contains("💡") || first.startsWith("Tip:",true) || first.startsWith("[!IMPORTANT]",true) -> CalloutType.TIP
                    first.contains("❓") || first.startsWith("Question:",true) -> CalloutType.QUESTION
                    first.contains("🔥") || first.startsWith("Hot:",true) -> CalloutType.FIRE
                    else -> null
                }
                if (calloutType != null) {
                    val title = when (calloutType) {
                        CalloutType.INFO -> first.removePrefix("[!NOTE]").trim().ifBlank { null }
                        CalloutType.SUCCESS -> first.removePrefix("[!TIP]").trim().ifBlank { null }
                        CalloutType.WARNING -> first.removePrefix("[!WARNING]").trim().ifBlank { null }
                        CalloutType.DANGER -> first.removePrefix("[!CAUTION]").trim().ifBlank { null }
                        CalloutType.TIP -> first.removePrefix("[!IMPORTANT]").trim().ifBlank { null }
                        else -> null
                    }
                    blocks.add(EnhancedBlock.Callout(calloutType, t, title))
                } else {
                    blocks.add(EnhancedBlock.Quote(ql, when { isGh -> QuoteType.GITHUB_ALERT; isTw -> QuoteType.TWEET; else -> QuoteType.NORMAL }))
                }; continue
            }
            // Checklist
            if (P_CHECK_ITEM_RE.matches(line)) {
                val items = mutableListOf<Pair<Boolean, String>>()
                while (i < lines.size && P_CHECK_ITEM_RE.matches(lines[i])) {
                    val m = P_CHECK_PARSE_RE.find(lines[i])
                    if (m != null) { items.add((m.groupValues[1].lowercase() == "x") to m.groupValues[2]); i++ } else { break } }
                blocks.add(EnhancedBlock.Checklist(items)); continue
            }
            // Tag chip
            val tm = P_TAG_RE.find(line.trim())
            if (tm != null) { blocks.add(EnhancedBlock.TagList(listOf(tm.groupValues[1] to tm.groupValues[2].trim()))); i++; continue }
            // Bullet
            if (P_BULLET_RE.matches(line) && !P_BULLET_NO_CHECK_RE.matches(line)) {
                val items = mutableListOf<String>(); val indents = mutableListOf<Int>()
                while (i < lines.size && P_BULLET_RE.matches(lines[i]) && !P_BULLET_NO_CHECK_RE.matches(lines[i])) {
                    val m = P_BULLET_PARSE_RE.find(lines[i]); if (m != null) { indents.add(m.groupValues[1].length / 2); items.add(m.groupValues[2]); i++ } else { break } }
                blocks.add(EnhancedBlock.BulletList(items, indents)); continue
            }
            // Numbered
            if (P_NUMBERED_RE.matches(line)) {
                val items = mutableListOf<String>(); val nums = mutableListOf<Int>()
                while (i < lines.size && P_NUMBERED_RE.matches(lines[i])) {
                    val m = P_NUMBERED_PARSE_RE.find(lines[i]); if (m != null) { nums.add(m.groupValues[1].toIntOrNull() ?: (items.size + 1)); items.add(m.groupValues[2]); i++ } else { break } }
                blocks.add(EnhancedBlock.NumberedList(items, nums)); continue
            }
            // Footnotes
            if (line.trim().startsWith("[^") && line.trim().contains("]:")) {
                val fns = mutableListOf<Pair<String, String>>()
                while (i < lines.size && P_FOOTNOTE_RE.matches(lines[i])) {
                    val m = P_FOOTNOTE_PARSE_RE.find(lines[i]); if (m != null) { fns.add(m.groupValues[1] to m.groupValues[2]); i++ } else { break } }
                if (fns.isNotEmpty()) blocks.add(EnhancedBlock.FootnoteSection(fns)); continue
            }
            // Table
            if (line.contains("|") && i + 1 < lines.size && P_TABLE_SEP_RE.matches(lines[i+1])) {
                val hdrs = line.trim().trim('|').split("|").map { it.trim() }; i += 2
                val align = if (i-1>=0 && lines[i-1].contains("|")) lines[i-1].trim().trim('|').split("|").map { c ->
                    when { c.startsWith(":") && c.endsWith(":") -> TextAlign.Center; c.endsWith(":") -> TextAlign.End; else -> TextAlign.Start }
                } else emptyList()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) { rows.add(lines[i].trim().trim('|').split("|").map { it.trim() }); i++ }
                blocks.add(EnhancedBlock.Table(hdrs, rows, align)); continue
            }
            // Spoiler
            if (P_SPOILER_RE.matches(line.trim()) && !line.contains("|  |")) {
                blocks.add(EnhancedBlock.Spoiler(line)); i++; continue
            }
            // Paragraph (catch-all) — ALWAYS makes forward progress. Lines that
            // look like block starts but don't fully match (a lone "| A | B |"
            // table row, "###", "- ", "1. ") must still be consumed, otherwise
            // streaming fragments would deadlock the parse loop and hang the UI.
            val pl = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("#") && !lines[i].trimStart().startsWith(">") &&
                !lines[i].trim().startsWith("```") && !lines[i].trim().startsWith("$$") &&
                !P_BULLET_BREAK_RE.matches(lines[i]) && !P_NUMBERED_BREAK_RE.matches(lines[i]) &&
                !lines[i].matches(P_HR_RE) &&
                !lines[i].trim().startsWith("<details") && !lines[i].trim().startsWith("</details") &&
                !P_FOOTNOTE_BREAK_RE.matches(lines[i]) &&
                !lines[i].trim().startsWith(":::") && !P_TAG_START_RE.matches(lines[i]) &&
                !P_TABLE_ROW_RE.matches(lines[i])) {
                pl.add(lines[i]); i++ }
            // Fallback: if every exclusion matched, consume the line verbatim.
            if (pl.isEmpty()) { pl.add(line); i++ }
            blocks.add(EnhancedBlock.Paragraph(pl.joinToString("\n")))
        }
        return blocks
    }
}

// ── Inline formatting (ChatGPT-level) ──────────────────────────────────────

private data class InlineResult(val annotated: AnnotatedString)
/** Max diff rows rendered before collapsing behind "Show all". */
private const val MAX_DIFF_VISIBLE_LINES = 20
/** Max table data rows rendered before collapsing behind "Show all". */
private const val MAX_TABLE_VISIBLE_ROWS = 40
/** Compiled once: used on every inline parse (every paragraph/list item). */
private val INLINE_AUTOLINK_RE = Regex("""(https?://[^\s<>]+|www\.[^\s<>]+)""")

private fun parseInline(text: String, linkColor: Color = Accent, codeBg: Color = SurfaceLight, codeColor: Color = Accent): InlineResult {
    val a = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Escaped markdown characters: \* \_ \# \[ \] \` \~ \| \\ \< \> etc.
            if (text[i] == '\\' && i+1 < text.length && text[i+1] in "*_#[]`~|\\<>$^") {
                append(text[i + 1]); i += 2; continue
            }
            // Autolinks: bare https://… and www.… URLs become tappable links.
            // Cheap guard keeps this O(n): find() only runs at chars that can
            // actually start a URL.
            val autolinkMatch = if ((text[i] == 'h' && text.startsWith("http", i)) ||
                (text[i] == 'w' && text.startsWith("www", i))) INLINE_AUTOLINK_RE.find(text, i) else null
            if (autolinkMatch != null && autolinkMatch.range.first == i) {
                var url = autolinkMatch.value
                // Strip trailing punctuation that is not part of the URL.
                while (url.isNotEmpty() && url.last() in ",.;:!?)]>\"'") url = url.dropLast(1)
                if (url.isNotEmpty()) {
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append("$url ↗") }
                    pop()
                    i = autolinkMatch.range.last + 1
                    continue
                }
            }
            // Math inline $...$ (rendered to Unicode)
            if (text[i] == '$' && i+1 < text.length && text[i+1] != '$') {
                val e = text.indexOf('$', i+1); if (e > 0) { withStyle(SpanStyle(fontFamily=FontFamily.Serif,color=VaultPurple,fontWeight=FontWeight.Medium,fontStyle=FontStyle.Italic)) { append(latexToUnicode(text.substring(i+1,e))) }; i=e+1; continue }
            }
            // Citations [1]
            if (text[i]=='[' && i+1<text.length && text[i+1].isDigit()) {
                val c=text.indexOf(']',i); if (c>0 && c+1<text.length && text[c+1]!='(') {
                    val n=text.substring(i+1,c); pushStringAnnotation("CITATION",n); withStyle(SpanStyle(color=linkColor,fontWeight=FontWeight.SemiBold,fontSize=12.sp,textDecoration=TextDecoration.Underline)){append("[$n]")}; pop(); i=c+1; continue } }
            // Footnotes [^id]
            if (text[i]=='[' && i+2<text.length && text[i+1]=='^') {
                val c=text.indexOf(']',i+2); if (c>0) { val id=text.substring(i+2,c); pushStringAnnotation("FOOTNOTE",id); withStyle(SpanStyle(color=VaultCyan,fontWeight=FontWeight.Medium,fontSize=11.sp,textDecoration=TextDecoration.Underline)){append("[^$id]")}; pop(); i=c+1; continue } }
            // Bold+Italic ***
            if (text.startsWith("***",i)) { val e=text.indexOf("***",i+3); if (e>0) { withStyle(SpanStyle(fontWeight=FontWeight.Bold,fontStyle=FontStyle.Italic)){append(text.substring(i+3,e))}; i=e+3; continue } }
            // Bold **
            if (text.startsWith("**",i) && !text.startsWith("***",i)) { val e=text.indexOf("**",i+2); if (e>0) { withStyle(SpanStyle(fontWeight=FontWeight.Bold)){append(text.substring(i+2,e))}; i=e+2; continue } }
            // Strikethrough ~~
            if (text.startsWith("~~",i)) { val e=text.indexOf("~~",i+2); if (e>0) { withStyle(SpanStyle(textDecoration=TextDecoration.LineThrough)){append(text.substring(i+2,e))}; i=e+2; continue } }
            // Inline code `
            if (text[i]=='`') { val e=text.indexOf('`',i+1); if (e>0) { withStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=codeBg,color=codeColor,fontSize=14.sp)){append(" "+text.substring(i+1,e)+" ")}; i=e+1; continue } }
            // Link [text](url)
            if (text[i]=='[') { val te=text.indexOf(']',i+1); if (te>0 && te+1<text.length && text[te+1]=='(') { val ue=text.indexOf(')',te+2); if (ue>0) { val lt=text.substring(i+1,te); val url=text.substring(te+2,ue); pushStringAnnotation("URL",url); withStyle(SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline)){append(lt); withStyle(SpanStyle(fontSize=10.sp,color=linkColor.copy(alpha=0.6f))){append(" ↗")}}; pop(); i=ue+1; continue } } }
            // Italic *
            if (text[i]=='*' && !text.startsWith("**",i) && !text.startsWith("***",i)) { val e=text.indexOf('*',i+1); if (e>0 && e>i+1) { withStyle(SpanStyle(fontStyle=FontStyle.Italic)){append(text.substring(i+1,e))}; i=e+1; continue } }
            // Keyboard <kbd>
            if (text.startsWith("<kbd>",i,true)) { val e=text.indexOf("</kbd>",i+5,true); if (e>0) { withStyle(SpanStyle(fontFamily=FontFamily.Monospace,fontSize=12.sp,fontWeight=FontWeight.Medium,color=TerminalWhite,background=SurfaceLight)){append(" [${text.substring(i+5,e)}] ")}; i=e+6; continue } }
            // Image ![alt](url)
            if (text[i]=='!' && i+1<text.length && text[i+1]=='[') { val te=text.indexOf(']',i+2); if (te>0 && te+1<text.length && text[te+1]=='(') { val ue=text.indexOf(')',te+2); if (ue>0) { val alt=text.substring(i+2,te); withStyle(SpanStyle(color=Accent.copy(alpha=0.7f),fontStyle=FontStyle.Italic)){append(" [🖼 $alt] ")}; i=ue+1; continue } } }
            append(text[i]); i++
        }
    }; return InlineResult(a)
}

@Composable private fun ClickableText(text: AnnotatedString, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, lineHeight: androidx.compose.ui.unit.TextUnit = 22.sp, fontWeight: FontWeight?=null, fontStyle: FontStyle? = null, textDecoration: TextDecoration?=null, textAlign: TextAlign?=null, modifier: Modifier=Modifier, onLinkClick: ((String)->Unit)?=null) {
    Text(text=text, color=color, fontSize=fontSize, lineHeight=lineHeight, fontWeight=fontWeight, fontStyle=fontStyle, textDecoration=textDecoration, textAlign=textAlign, modifier=modifier.clickable { text.getStringAnnotations("URL",0,text.length).firstOrNull()?.let { onLinkClick?.invoke(it.item) } })
}

// ── Block renderers ────────────────────────────────────────────────────────

@Composable private fun HeadingBlock(b: EnhancedBlock.Heading) {
    val (sz, w, c) = when(b.level){1-> Triple(26.sp,FontWeight.Bold,TerminalWhite);2-> Triple(22.sp,FontWeight.Bold,Accent);3-> Triple(19.sp,FontWeight.SemiBold,TerminalWhite);4-> Triple(17.sp,FontWeight.Medium,TerminalWhite.copy(0.9f));5-> Triple(15.sp,FontWeight.Medium,TerminalWhite.copy(0.8f));else-> Triple(14.sp,FontWeight.Normal,TerminalWhite.copy(0.7f)) }
    val result = remember(b.text) { parseInline(b.text) }; val align = if (b.style==HeadingStyle.CENTERED) TextAlign.Center else TextAlign.Start
    val pad = when(b.style){HeadingStyle.CENTERED->Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp);HeadingStyle.WITH_LINE->Modifier.fillMaxWidth().padding(top=12.dp,bottom=2.dp); else->Modifier.padding(top=10.dp,bottom=2.dp)}
    Column(pad) {
        Text(text=result.annotated,color=c,fontSize=sz,fontWeight=w,lineHeight=(sz.value*1.3f).sp,textAlign=align,modifier=Modifier.fillMaxWidth())
        if (b.style==HeadingStyle.WITH_LINE) { Spacer(Modifier.height(4.dp)); Box(Modifier.fillMaxWidth().height(2.dp).background(Accent.copy(alpha=0.3f))) } }
}

@Composable private fun ParagraphBlock(b: EnhancedBlock.Paragraph, onLinkClick: ((String)->Unit)?) { val r = remember(b.text) { parseInline(b.text) }; ClickableText(text=r.annotated,color=TerminalWhite,fontSize=15.sp,lineHeight=22.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) }

@Composable private fun BulletListBlock(b: EnhancedBlock.BulletList, onLinkClick: ((String)->Unit)?) {
    Column(Modifier.fillMaxWidth()) { b.items.forEachIndexed { idx,item -> val indent = b.indentLevels.getOrNull(idx)?:0; Row(Modifier.fillMaxWidth().padding(start=(indent*20).dp,bottom=3.dp),verticalAlignment=Alignment.Top){
        Text(text=when(indent%3){0->"•";1->"◦";else->"▪"},color=Accent,fontSize=16.sp,modifier=Modifier.padding(top=2.dp,end=8.dp).width(16.dp))
        val r=remember(item){parseInline(item)}; ClickableText(text=r.annotated,color=TerminalWhite,fontSize=15.sp,lineHeight=22.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) } } }
}

@Composable private fun NumberedListBlock(b: EnhancedBlock.NumberedList, onLinkClick: ((String)->Unit)?) {
    Column(Modifier.fillMaxWidth()) { b.items.forEachIndexed{idx,item -> val num=b.numbers.getOrNull(idx)?:(idx+1); Row(Modifier.fillMaxWidth().padding(bottom=3.dp),verticalAlignment=Alignment.Top){
        Text("$num.",color=Accent,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.width(28.dp))
        val r=remember(item){parseInline(item)}; ClickableText(text=r.annotated,color=TerminalWhite,fontSize=15.sp,lineHeight=22.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) } } }
}

@Composable private fun ChecklistBlock(b: EnhancedBlock.Checklist, onLinkClick: ((String)->Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        val done=b.items.count{it.first}
        if (b.items.size>3) { Row(Modifier.fillMaxWidth().padding(bottom=6.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Progress: $done/${b.items.size}",color=Accent.copy(0.7f),fontSize=12.sp); Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceLight)){Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Success))} } }
        b.items.forEach{(checked,text)->
            Row(Modifier.fillMaxWidth().padding(bottom=3.dp),verticalAlignment=Alignment.Top){
                Box(Modifier.padding(top=3.dp,end=8.dp).size(18.dp).clip(RoundedCornerShape(4.dp)).background(if(checked)Success else SurfaceLight).border(1.5.dp,if(checked)Success else TerminalWhite.copy(0.4f),RoundedCornerShape(4.dp)),contentAlignment=Alignment.Center){if(checked)Text("✓",color=Background800,fontSize=12.sp,fontWeight=FontWeight.Bold)}
                val r=remember(text){parseInline(text)}; ClickableText(text=r.annotated,color=if(checked)TerminalWhite.copy(0.6f)else TerminalWhite,fontSize=15.sp,lineHeight=22.sp,textDecoration=if(checked)TextDecoration.LineThrough else TextDecoration.None,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) } } }
}

@Composable private fun CodeBlockBlock(b: EnhancedBlock.CodeBlock) {
    ExpandableCodeBlock(code = b.code, language = b.language)
}

@Composable private fun DiffBlockBlock(b: EnhancedBlock.DiffBlock) {
    val clip=LocalClipboardManager.current; var copied by remember{mutableStateOf(false)}
    // Cap huge diffs so a giant patch doesn't render thousands of rows at once.
    val isLong = b.lines.size > MAX_DIFF_VISIBLE_LINES
    var expanded by remember { mutableStateOf(!isLong) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1B1E)).border(1.dp,SurfaceLight,RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF2A2B30)).padding(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){ Text("diff ${b.language}",color=VaultPurple,fontSize=12.sp,fontFamily=FontFamily.Monospace)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if (isLong) {
                Text(
                    if (expanded) "Collapse" else "Expand",
                    color = TerminalWhite.copy(0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
            val copyIconColor = if (copied) Success else TerminalWhite.copy(0.6f)
            val copyLabel = if (copied) "Copied" else "Copy"
            Row(Modifier.clickable{clip.setText(AnnotatedString(b.lines.joinToString("\n"){it.text}));copied=true},verticalAlignment=Alignment.CenterVertically){ Icon(Icons.Default.ContentCopy,null,tint=copyIconColor,modifier=Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(copyLabel,color=copyIconColor,fontSize=11.sp) } } }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=4.dp)) { val shown = if (expanded || !isLong) b.lines else b.lines.take(MAX_DIFF_VISIBLE_LINES); shown.forEach{l-> val(bg,tc,pre)=when(l.type){DiffType.ADDED-> Triple(Success.copy(0.12f),Success,"+");DiffType.REMOVED-> Triple(Danger.copy(0.12f),Danger,"-");DiffType.HEADER-> Triple(VaultPurple.copy(0.1f),VaultPurple,"@@");DiffType.CONTEXT-> Triple(Color.Transparent,TerminalWhite.copy(0.7f)," ") }; Text("$pre ${l.text}",color=tc,fontSize=13.sp,fontFamily=FontFamily.Monospace,lineHeight=18.sp,modifier=Modifier.fillMaxWidth().background(bg).padding(horizontal=12.dp,vertical=0.dp)) } }
        if (!expanded && isLong) {
            Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.Center){
                Text("▼ Show all ${b.lines.size} lines",color=Accent.copy(0.6f),fontSize=11.sp,fontFamily=FontFamily.Monospace) } }
    }
}

@Composable private fun QuoteBlock(b: EnhancedBlock.Quote, onLinkClick: ((String)->Unit)?) {
    val (bg,br,bw)=when(b.quoteType){QuoteType.NORMAL-> Triple(SurfaceLight.copy(0.12f),TerminalWhite.copy(0.3f),3.dp);QuoteType.TWEET-> Triple(Color(0xFF1DA1F2).copy(0.08f),Color(0xFF1DA1F2),2.dp);QuoteType.GITHUB_ALERT-> Triple(Color(0xFF0969DA).copy(0.08f),Color(0xFF0969DA),2.dp) }
    Column(Modifier.fillMaxWidth().padding(start=8.dp).clip(RoundedCornerShape(6.dp)).background(bg).border(bw,br,RoundedCornerShape(6.dp)).padding(12.dp)) { b.lines.forEach{val r=remember(it){parseInline(it)}; ClickableText(text=r.annotated,color=TerminalWhite.copy(0.85f),fontSize=14.sp,fontStyle=FontStyle.Italic,lineHeight=20.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick)} }
}

@Composable private fun TableBlock(b: EnhancedBlock.Table) {
    val colCount = b.headers.size.coerceAtLeast(b.rows.firstOrNull()?.size ?: 0)
    if (colCount == 0) return
    // Cap very wide/long tables so 200+ row responses don't compose thousands
    // of Text nodes at once; the rest is one tap away.
    val isLong = b.rows.size > MAX_TABLE_VISIBLE_ROWS
    var expanded by remember { mutableStateOf(!isLong) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp,SurfaceLight,RoundedCornerShape(8.dp)).background(Color(0xFF1A1B1E)).horizontalScroll(rememberScrollState())) {
        // Header row — evenly weighted columns for proper alignment
        Row(Modifier.background(Accent.copy(0.2f)).padding(vertical=10.dp, horizontal=4.dp)) {
            repeat(colCount) { i ->
                val h = b.headers.getOrNull(i) ?: ""
                val a = b.alignment.getOrNull(i) ?: TextAlign.Start
                Text(h, color=Accent, fontSize=13.sp, fontWeight=FontWeight.Bold, textAlign=a,
                    modifier=Modifier.weight(1f).padding(horizontal=8.dp))
            }
        }
        // Data rows — same weighted columns (capped for huge tables)
        val shownRows = if (expanded || !isLong) b.rows else b.rows.take(MAX_TABLE_VISIBLE_ROWS)
        shownRows.forEachIndexed { ri, r ->
            Row(Modifier.background(if(ri%2==1)SurfaceLight.copy(0.08f)else Color.Transparent).padding(vertical=8.dp, horizontal=4.dp)) {
                repeat(colCount) { ci ->
                    val c = r.getOrNull(ci) ?: ""
                    val a = b.alignment.getOrNull(ci) ?: TextAlign.Start
                    val rs = remember(c) { parseInline(c) }
                    Text(text=rs.annotated, color=TerminalWhite, fontSize=13.sp, textAlign=a,
                        modifier=Modifier.weight(1f).padding(horizontal=8.dp, vertical=2.dp))
                }
            }
        }
        if (!expanded && isLong) {
            Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.Center){
                Text("▼ Show all ${b.rows.size} rows",color=Accent.copy(0.6f),fontSize=11.sp,fontFamily=FontFamily.Monospace) } }
        if (b.rows.isNotEmpty()) Box(Modifier.fillMaxWidth().background(Accent.copy(0.05f)).padding(horizontal=12.dp,vertical=4.dp)){Text("${b.rows.size} rows",color=TerminalWhite.copy(0.3f),fontSize=10.sp)} }
}

@Composable private fun CalloutBlock(b: EnhancedBlock.Callout, onLinkClick: ((String)->Unit)?) {
    val (bg,br,ic,lc,ti)=when(b.type){
        CalloutType.INFO-> listOf(SurfaceLight.copy(0.15f),Accent,"ℹ️",Accent,"Note")
        CalloutType.SUCCESS-> listOf(Success.copy(0.12f),Success,"✅",Success,"Done")
        CalloutType.WARNING-> listOf(TerminalYellow.copy(0.12f),TerminalYellow,"⚠️",TerminalYellow,"Warning")
        CalloutType.DANGER-> listOf(Danger.copy(0.12f),Danger,"🔴",Danger,"Danger")
        CalloutType.TIP-> listOf(VaultPurple.copy(0.12f),VaultPurple,"💡",VaultPurple,"Tip")
        CalloutType.QUESTION-> listOf(VaultCyan.copy(0.12f),VaultCyan,"❓",VaultCyan,"Question")
        CalloutType.FIRE-> listOf(TerminalYellow.copy(0.15f),Color(0xFFFF6B35),"🔥",Color(0xFFFF6B35),"Hot")
    }
    val title = b.title ?: (ti as String)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg as Color).border(1.dp,br as Color,RoundedCornerShape(10.dp)).padding(12.dp),verticalAlignment=Alignment.Top) {
        Text(ic as String,color=lc as Color,fontSize=18.sp,modifier=Modifier.padding(end=10.dp,top=1.dp))
        Column(Modifier.fillMaxWidth()){ Text(title,color=lc,fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=4.dp))
            val ct = b.text.lines().dropWhile{it.startsWith("[!")||it.startsWith("⚠")||it.startsWith("✅")||it.startsWith("ℹ")}.joinToString("\n").trim()
            val r=remember(b.text) { parseInline(ct) }; ClickableText(text=r.annotated,color=TerminalWhite,fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) } }
}

@Composable private fun MathBlock(b: EnhancedBlock.MathBlock) {
    val rendered = remember(b.formula) { latexToUnicode(b.formula) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(VaultPurple.copy(0.08f)).border(1.dp,VaultPurple.copy(0.2f),RoundedCornerShape(8.dp)).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(rendered,color=VaultPurple,fontSize=if(b.display)20.sp else 15.sp,fontWeight=FontWeight.Medium,lineHeight=if(b.display)28.sp else 22.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
        if (rendered != b.formula) Text(b.formula,color=VaultPurple.copy(0.4f),fontSize=10.sp,fontFamily=FontFamily.Monospace,fontStyle=FontStyle.Italic,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=4.dp))
        Text(if(b.display)"📐 Formula" else "📐 Inline math",color=VaultPurple.copy(0.4f),fontSize=10.sp,fontFamily=FontFamily.Monospace,modifier=Modifier.padding(top=4.dp)) }
}

@Composable private fun CollapsibleBlock(b: EnhancedBlock.Collapsible, onLinkClick: ((String)->Unit)?) {
    var exp by remember{mutableStateOf(b.open)}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp,SurfaceLight,RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().background(SurfaceLight.copy(0.15f)).clickable{exp=!exp}.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            val r=parseInline(b.summary); ClickableText(text=r.annotated,color=TerminalWhite,fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),onLinkClick=onLinkClick)
            Icon(if(exp)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null,tint=TerminalWhite.copy(0.5f),modifier=Modifier.size(20.dp)) }
        if (exp) Box(Modifier.fillMaxWidth().padding(12.dp)){RichMarkdownText(markdown=b.content,onLinkClick=onLinkClick)} }
}

@Composable private fun DefinitionListBlock(b: EnhancedBlock.DefinitionList, onLinkClick: ((String)->Unit)?) {
    Column(Modifier.fillMaxWidth()) { b.terms.forEach{(t,d)-> Column(Modifier.fillMaxWidth().padding(vertical=4.dp).clip(RoundedCornerShape(6.dp)).background(SurfaceLight.copy(0.08f)).padding(8.dp)){
        val tr=remember(t){parseInline(t)}; Text(text=tr.annotated,color=Accent,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=2.dp))
        val dr=remember(d){parseInline(d)}; ClickableText(text=dr.annotated,color=TerminalWhite.copy(0.85f),fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.padding(start=8.dp),onLinkClick=onLinkClick) } } }
}

@Composable private fun FootnoteSectionBlock(b: EnhancedBlock.FootnoteSection, onLinkClick: ((String)->Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top=12.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceLight.copy(0.08f)).padding(12.dp)) {
        Text("📝 References",color=Accent,fontSize=14.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=8.dp))
        b.footnotes.forEach{(id,t)-> Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.Top){ Text("[^$id]",color=VaultCyan,fontSize=12.sp,fontWeight=FontWeight.Medium,modifier=Modifier.width(36.dp)); val r=remember(t){parseInline(t)}; ClickableText(text=r.annotated,color=TerminalWhite.copy(0.75f),fontSize=13.sp,lineHeight=18.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) } } }
}

@Composable private fun MermaidBlock(b: EnhancedBlock.Mermaid) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val codeVisible = remember(b.code) { b.code }
    var renderRequested by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F2937)).border(1.dp,VaultCyan.copy(0.3f),RoundedCornerShape(8.dp)).padding(12.dp)) {
        // ── Header ───────────────────────────────────────────────────────
        Row(verticalAlignment=Alignment.CenterVertically){
            Text("📊",fontSize=18.sp); Spacer(Modifier.width(8.dp))
            Text("Diagram",color=VaultCyan,fontSize=13.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // Copy code button
            Surface(shape=RoundedCornerShape(6.dp),color=VaultCyan.copy(0.12f),
                modifier=Modifier.clickable{
                    clipboard.setText(AnnotatedString(b.code))
                }) {
                Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                    Icon(Icons.Default.ContentCopy,null,tint=VaultCyan,modifier=Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Code",color=VaultCyan,fontSize=10.sp,fontFamily=FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Code ────────────────────────────────────────────────────────
        Text(codeVisible,color=TerminalWhite.copy(0.8f),fontSize=12.sp,
            fontFamily=FontFamily.Monospace,lineHeight=17.sp,
            modifier=Modifier.fillMaxWidth().background(Color(0xFF111827))
                .clip(RoundedCornerShape(6.dp)).padding(10.dp))

        Spacer(Modifier.height(6.dp))

        // ── Action buttons ──────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            // Render Diagram button
            Surface(shape=RoundedCornerShape(6.dp),color=Accent.copy(0.12f),
                border=BorderStroke(1.dp,Accent.copy(0.25f)),
                modifier=Modifier.clickable{ renderRequested = !renderRequested }) {
                Row(Modifier.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    val icon = if (renderRequested) "📋" else "🧪"
                    val label = if (renderRequested) "Hide" else "Render Diagram"
                    Text(icon,fontSize=12.sp)
                    Text(label,color=Accent,fontSize=11.sp,fontWeight=FontWeight.Medium)
                }
            }
            // Open in Browser button
            Surface(shape=RoundedCornerShape(6.dp),color=VaultCyan.copy(0.12f),
                border=BorderStroke(1.dp,VaultCyan.copy(0.25f)),
                modifier=Modifier.clickable{
                    val enc = encodeMermaidBase64(b.code)
                    val u = "https://mermaid.ink/img/" + enc
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(i) }
                }) {
                Row(Modifier.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("🌐",fontSize=12.sp)
                    Text("Open",color=VaultCyan,fontSize=11.sp,fontWeight=FontWeight.Medium)
                }
            }
        }

        // ── Rendered image (cached via Coil) ────────────────────────────
        if (renderRequested) {
            Spacer(Modifier.height(8.dp))
            val encoded = remember(b.code) { encodeMermaidBase64(b.code) }
            val imageUrl = "https://mermaid.ink/img/" + encoded + "?type=png&bgColor=1f2937"

            val imageLoader = remember { ImageCacheUtil.getImageLoader(context) }
            val request = remember(imageUrl) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCacheKey(imageUrl)
                    .diskCacheKey(imageUrl)
                    .size(Size(800, 600))
                    .crossfade(true)
                    .build()
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF111827))) {
                // Loading indicator (visible until image crossfades in)
                Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.Center){
                    CircularProgressIndicator(Modifier.size(20.dp),color=VaultCyan,strokeWidth=2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Rendering...",color=VaultCyan.copy(0.6f),fontSize=11.sp)
                }

                AsyncImage(
                    model = request,
                    contentDescription = "Rendered Mermaid diagram",
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Mermaid diagram",color=TerminalWhite.copy(0.25f),fontSize=9.sp,fontStyle=FontStyle.Italic)
    }
}

/** Encode Mermaid code to URL-safe base64 for mermaid.ink */
private fun encodeMermaidBase64(code: String): String {
    val input = code.toByteArray(Charsets.UTF_8)
    return Base64.encodeToString(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

@Composable private fun FileTreeBlock(b: EnhancedBlock.FileTree) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1B1E)).border(1.dp,SurfaceLight,RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        Text("📁 Project Structure",color=Accent,fontSize=12.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.Monospace,modifier=Modifier.padding(bottom=8.dp))
        Text(text=b.tree,color=TerminalWhite.copy(0.85f),fontSize=12.sp,fontFamily=FontFamily.Monospace,lineHeight=18.sp) }
}

@Composable private fun TagListBlock(b: EnhancedBlock.TagList) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        b.tags.forEach{(l,v)-> val tc=when(l.lowercase()){"api","endpoint"->Accent;"bug","issue"->Danger;"feature","enhancement"->Success;"docs"->VaultCyan;"security"->TerminalYellow;"version","v"->VaultPurple;else->SurfaceLight }
            Surface(shape=RoundedCornerShape(6.dp),color=tc.copy(0.15f),border=androidx.compose.foundation.BorderStroke(1.dp,tc.copy(0.3f))){ Text("$l: $v",color=tc,fontSize=11.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Medium,modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp)) } } }
}

@Composable private fun SpoilerBlock(b: EnhancedBlock.Spoiler, onLinkClick: ((String)->Unit)?) {
    var rev by remember{mutableStateOf(false)}
    val revealedText = remember(b.text) { b.text.replace("||", "") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if(rev)SurfaceLight.copy(0.1f)else Color(0xFF1A1A1A)).border(1.dp,if(rev)TerminalWhite.copy(0.2f)else SurfaceLight,RoundedCornerShape(6.dp)).clickable{rev=!rev}.padding(horizontal=12.dp,vertical=8.dp)) {
        if (rev) { val r=remember(revealedText){parseInline(revealedText)}; ClickableText(text=r.annotated,color=TerminalWhite,fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.fillMaxWidth(),onLinkClick=onLinkClick) }
        else { Row(verticalAlignment=Alignment.CenterVertically){ Text("⬤⬤⬤ Spoiler ⬤⬤⬤",color=TerminalWhite.copy(0.4f),fontSize=14.sp); Spacer(Modifier.width(8.dp)); Text("Tap to reveal",color=TerminalWhite.copy(0.2f),fontSize=11.sp) } } }
}

@Composable private fun ImagePlaceholderBlock(b: EnhancedBlock.ImagePlaceholder) {
    val context = LocalContext.current
    val altText = remember(b.alt) { b.alt }
    val urlText = remember(b.url) { b.url?.trim() }

    if (urlText != null && urlText.isNotBlank() &&
        (urlText.startsWith("http://") || urlText.startsWith("https://"))) {
        // ── Load actual image from URL with Coil caching ────────────────
        val imageLoader = remember { ImageCacheUtil.getImageLoader(context) }
        val request = remember(urlText) {
            ImageRequest.Builder(context)
                .data(urlText)
                .memoryCacheKey(urlText)
                .diskCacheKey(urlText)
                .crossfade(true)
                .build()
        }
        var failed by remember(urlText) { mutableStateOf(false) }
        var showViewer by remember(urlText) { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceLight.copy(0.08f))
                    .border(1.dp, SurfaceLight.copy(0.3f), RoundedCornerShape(10.dp))
            ) {
                if (failed) {
                    // ── Error state ────────────────────────────────────────
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🖼️", fontSize = 24.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Image failed to load", color = Danger, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            urlText.takeLast(40),
                            color = TerminalWhite.copy(0.4f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Accent.copy(0.12f),
                            border = BorderStroke(1.dp, Accent.copy(0.3f)),
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(urlText))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("🌐", fontSize = 11.sp)
                                Text("Open in browser", color = Accent, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    // Loading indicator (behind image, shown until crossfade completes)
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            color = Accent,
                            strokeWidth = 2.dp
                        )
                    }

                    // Actual image loaded via Coil (with disk cache). Tap to open
                    // the full-screen zoomable viewer.
                    AsyncImage(
                        model = request,
                        contentDescription = altText,
                        imageLoader = imageLoader,
                        onError = { failed = true },
                        onSuccess = { failed = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showViewer = true },
                        contentScale = ContentScale.Fit
                    )
                }

                // Full-screen hint badge (overlaid on the image)
                if (!failed) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clickable { showViewer = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.ZoomIn, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Text("Full screen", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Caption / URL
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(altText, color = TerminalWhite.copy(0.4f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                val displayUrl = urlText.takeLast(30)
                Text(displayUrl, color = Accent.copy(0.5f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (showViewer) {
            FullScreenImageViewer(url = urlText, onDismiss = { showViewer = false })
        }
    } else {
        // ── No URL — show placeholder only ──────────────────────────────
        Box(Modifier.fillMaxWidth().heightIn(min=60.dp).clip(RoundedCornerShape(8.dp))
            .background(SurfaceLight.copy(0.1f))
            .border(1.dp,SurfaceLight,RoundedCornerShape(8.dp)).padding(16.dp),
            contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text("🖼️",fontSize=24.sp)
                Spacer(Modifier.height(4.dp))
                Text(altText,color=TerminalWhite.copy(0.5f),fontSize=12.sp,textAlign=TextAlign.Center)
                if(urlText!=null && urlText.isNotBlank()) Text("🔗 ${urlText.takeLast(40)}",color=Accent.copy(0.5f),fontSize=10.sp,fontFamily=FontFamily.Monospace)
            }
        }
    }
}

@Composable private fun HorizontalRuleBlock() { Box(Modifier.fillMaxWidth().padding(vertical=8.dp).height(1.dp).background(TerminalWhite.copy(0.15f))) }

// ── Full-screen zoomable image viewer ────────────────────────────────────
// Tap an inline markdown image to open it full screen on a black backdrop.
// Pinch to zoom (1x–8x), use the +/- buttons, and tap anywhere to close.
@Composable
private fun FullScreenImageViewer(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember { ImageCacheUtil.getImageLoader(context) }
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .crossfade(true)
            .build()
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Zoomable image area
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            val maxOffset = (scale - 1f) * 600f
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxOffset, maxOffset),
                                (offset.y + pan.y).coerceIn(-maxOffset, maxOffset)
                            )
                        }
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = "Full screen image",
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Top-right controls: zoom %, open in browser, close
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.12f)) {
                    Text(
                        "${(scale * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier
                        .size(38.dp)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.OpenInNew, "Open in browser", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier
                        .size(38.dp)
                        .clickable(onClick = onDismiss)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Bottom zoom controls
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ZoomCircleButton(Icons.Default.ZoomOut, "Zoom out") {
                    // Re-clamp the pan offset with the new scale so the image can
                    // never get stranded off-screen after button zooming.
                    val newScale = (scale / 1.5f).coerceIn(1f, 8f)
                    scale = newScale
                    val maxOffset = (newScale - 1f) * 600f
                    offset = Offset(
                        offset.x.coerceIn(-maxOffset, maxOffset),
                        offset.y.coerceIn(-maxOffset, maxOffset)
                    )
                }
                Text(
                    "Pinch to zoom",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                ZoomCircleButton(Icons.Default.ZoomIn, "Zoom in") {
                    val newScale = (scale * 1.5f).coerceIn(1f, 8f)
                    scale = newScale
                    val maxOffset = (newScale - 1f) * 600f
                    offset = Offset(
                        offset.x.coerceIn(-maxOffset, maxOffset),
                        offset.y.coerceIn(-maxOffset, maxOffset)
                    )
                }
            }

            // Reset button
            if (scale > 1.01f || offset != Offset.Zero) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .clickable {
                            scale = 1f
                            offset = Offset.Zero
                        }
                ) {
                    Text(
                        "Reset",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
        modifier = Modifier
            .size(42.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Math (LaTeX → Unicode, zero dependencies) ─────────────────────────────
// Full renderer lives in LatexRenderer.kt — matrices, vectors/accents,
// limits, binomials, aligned environments, \text{}, nested fractions and
// 150+ symbols. Anything unrecognized renders literally (never crashes).

private fun latexToUnicode(latex: String): String = LatexRenderer.toUnicode(latex)

// ── Helpers ────────────────────────────────────────────────────────────────

private fun getLangColor(lang:String):Color = when(lang.lowercase()){
    "kotlin","kt","java"->Color(0xFF7F52FF);"python","py"->Color(0xFF3776AB);"javascript","js"->Color(0xFFF7DF1E);"typescript","ts"->Color(0xFF3178C6);"go"->Color(0xFF00ADD8);"rust","rs"->Color(0xFFDEA584);"cpp","c++","c"->Color(0xFF00599C);"swift"->Color(0xFFF05138);"ruby","rb"->Color(0xFFCC342D);"php"->Color(0xFF777BB4);"shell","bash","sh","zsh"->Color(0xFF4EAA25);"sql"->Color(0xFFE38C00);"html","xml","svg"->Color(0xFFE34F26);"css","scss"->Color(0xFF1572B6);"json","yaml","yml","toml"->Color(0xFF5B5B5B);"docker","dockerfile"->Color(0xFF2496ED);"diff"->VaultPurple;"mermaid"->VaultCyan;else->Accent }
