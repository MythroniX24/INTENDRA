package com.interndra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ui.theme.Accent
import com.interndra.ui.theme.ChatBg
import com.interndra.ui.theme.Danger
import com.interndra.ui.theme.SurfaceLight
import com.interndra.ui.theme.Success
import com.interndra.ui.theme.TerminalWhite
import com.interndra.ui.theme.TerminalYellow

/**
 * RichMarkdownText — Phase 3 premium AI chat renderer.
 *
 * A native-Compose markdown renderer that supports:
 *   - Headings (#, ##, ###)
 *   - Bullet lists (-, *)
 *   - Numbered lists (1., 2.)
 *   - Checklists (- [ ], - [x])
 *   - Tables (pipe syntax)
 *   - Block quotes (> with ℹ/✓/⚠ callout detection)
 *   - Code blocks (``` fenced) with copy button
 *   - Inline code (`code`)
 *   - Bold (**text**), Italic (*text*), Strikethrough (~~text~~)
 *   - Links ([text](url))
 *   - Horizontal rules (---)
 */
@Composable
fun RichMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading     -> HeadingBlock(block)
                is MarkdownBlock.Paragraph   -> ParagraphBlock(block, onLinkClick)
                is MarkdownBlock.BulletList  -> BulletListBlock(block, onLinkClick)
                is MarkdownBlock.NumberedList -> NumberedListBlock(block, onLinkClick)
                is MarkdownBlock.Checklist   -> ChecklistBlock(block, onLinkClick)
                is MarkdownBlock.CodeBlock   -> CodeBlockBlock(block)
                is MarkdownBlock.Quote       -> QuoteBlock(block, onLinkClick)
                is MarkdownBlock.Table       -> TableBlock(block)
                is MarkdownBlock.HorizontalRule -> HorizontalRuleBlock()
                is MarkdownBlock.Callout     -> CalloutBlock(block, onLinkClick)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ── Parser ────────────────────────────────────────────────────────────────

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class BulletList(val items: List<String>, val indentLevels: List<Int>) : MarkdownBlock()
    data class NumberedList(val items: List<String>, val numbers: List<Int>) : MarkdownBlock()
    data class Checklist(val items: List<Pair<Boolean, String>>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class Quote(val lines: List<String>) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Callout(val type: CalloutType, val text: String) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
}

enum class CalloutType { INFO, SUCCESS, WARNING, DANGER, QUOTE }

object MarkdownParser {
    fun parse(md: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (line.isBlank()) { i++; continue }

            if (line.matches(Regex("""\s*([-*_])\1{2,}\s*"""))) {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++; continue
            }

            val headingMatch = Regex("""^(#{1,6})\s+(.+)""").find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level, text))
                i++; continue
            }

            if (line.trim().startsWith("```")) {
                val lang = line.trim().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                i++
                blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            if (line.contains("|") && i + 1 < lines.size &&
                Regex("""^\s*\|?[\s:|-]+\|?\s*$""").matches(lines[i + 1])) {
                val headers = splitTableRow(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                blocks.add(MarkdownBlock.Table(headers, rows))
                continue
            }

            if (line.trimStart().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines.add(lines[i].trimStart().removePrefix(">").trim())
                    i++
                }
                val first = quoteLines.firstOrNull().orEmpty()
                val callout = when {
                    first.startsWith("⚠", ignoreCase = true) ||
                    first.startsWith("Warning:", ignoreCase = true) ||
                    first.contains("⚠️") -> MarkdownBlock.Callout(CalloutType.WARNING, quoteLines.joinToString("\n"))
                    first.startsWith("✓", ignoreCase = true) ||
                    first.startsWith("Done:", ignoreCase = true) ||
                    first.contains("✅") -> MarkdownBlock.Callout(CalloutType.SUCCESS, quoteLines.joinToString("\n"))
                    first.startsWith("ℹ", ignoreCase = true) ||
                    first.startsWith("Note:", ignoreCase = true) ||
                    first.contains("ℹ️") -> MarkdownBlock.Callout(CalloutType.INFO, quoteLines.joinToString("\n"))
                    first.startsWith("🔴", ignoreCase = true) ||
                    first.startsWith("Danger:", ignoreCase = true) -> MarkdownBlock.Callout(CalloutType.DANGER, quoteLines.joinToString("\n"))
                    else -> MarkdownBlock.Quote(quoteLines)
                }
                blocks.add(callout)
                continue
            }

            if (Regex("""^\s*[-*]\s+\[[ xX]\]\s+.+""").matches(line)) {
                val items = mutableListOf<Pair<Boolean, String>>()
                while (i < lines.size && Regex("""^\s*[-*]\s+\[[ xX]\]\s+.+""").matches(lines[i])) {
                    val m = Regex("""^\s*[-*]\s+\[([ xX])\]\s+(.+)""").find(lines[i])!!
                    items.add((m.groupValues[1].lowercase() == "x") to m.groupValues[2])
                    i++
                }
                blocks.add(MarkdownBlock.Checklist(items))
                continue
            }

            if (Regex("""^\s*[-*]\s+.+""").matches(line)) {
                val items = mutableListOf<String>()
                val indents = mutableListOf<Int>()
                while (i < lines.size && Regex("""^\s*[-*]\s+.+""").matches(lines[i])) {
                    val m = Regex("""^(\s*)[-*]\s+(.+)""").find(lines[i])!!
                    indents.add(m.groupValues[1].length / 2)
                    items.add(m.groupValues[2])
                    i++
                }
                blocks.add(MarkdownBlock.BulletList(items, indents))
                continue
            }

            if (Regex("""^\s*\d+\.\s+.+""").matches(line)) {
                val items = mutableListOf<String>()
                val numbers = mutableListOf<Int>()
                while (i < lines.size && Regex("""^\s*\d+\.\s+.+""").matches(lines[i])) {
                    val m = Regex("""^\s*(\d+)\.\s+(.+)""").find(lines[i])!!
                    numbers.add(m.groupValues[1].toIntOrNull() ?: (items.size + 1))
                    items.add(m.groupValues[2])
                    i++
                }
                blocks.add(MarkdownBlock.NumberedList(items, numbers))
                continue
            }

            val paraLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                   !lines[i].trimStart().startsWith("#") &&
                   !lines[i].trimStart().startsWith(">") &&
                   !lines[i].trim().startsWith("```") &&
                   !Regex("""^\s*[-*]\s+""").matches(lines[i]) &&
                   !Regex("""^\s*\d+\.\s+""").matches(lines[i]) &&
                   !lines[i].matches(Regex("""\s*([-*_])\1{2,}\s*"""))) {
                paraLines.add(lines[i])
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
            }
        }
        return blocks
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }
}

// ── Inline formatting ─────────────────────────────────────────────────────

fun parseInlineMarkdown(
    text: String,
    linkColor: Color = Accent,
    codeBackground: Color = SurfaceLight,
    codeTextColor: Color = Accent
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("***", i)) {
            val end = text.indexOf("***", i + 3)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 3, end))
                }
                i = end + 3
                continue
            }
        }
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > 0) {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    color = codeTextColor
                )) {
                    append(" " + text.substring(i + 1, end) + " ")
                }
                i = end + 1
                continue
            }
        }
        if (text[i] == '[') {
            val textEnd = text.indexOf(']', i + 1)
            if (textEnd > 0 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                val urlEnd = text.indexOf(')', textEnd + 2)
                if (urlEnd > 0) {
                    val linkText = text.substring(i + 1, textEnd)
                    val url = text.substring(textEnd + 2, urlEnd)
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                    pop()
                    i = urlEnd + 1
                    continue
                }
            }
        }
        if (text[i] == '*') {
            val end = text.indexOf('*', i + 1)
            if (end > 0 && end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

// ── Block renderers ───────────────────────────────────────────────────────

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val (size, weight, color) = when (block.level) {
        1    -> Triple(22.sp, FontWeight.Bold, TerminalWhite)
        2    -> Triple(19.sp, FontWeight.Bold, Accent)
        3    -> Triple(17.sp, FontWeight.SemiBold, TerminalWhite)
        4    -> Triple(16.sp, FontWeight.Medium, TerminalWhite)
        5    -> Triple(15.sp, FontWeight.Medium, TerminalWhite.copy(alpha = 0.9f))
        else -> Triple(14.sp, FontWeight.Normal, TerminalWhite.copy(alpha = 0.8f))
    }
    Text(
        text = parseInlineMarkdown(block.text),
        color = color,
        fontSize = size,
        fontWeight = weight,
        lineHeight = (size.value * 1.3f).sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun ParagraphBlock(block: MarkdownBlock.Paragraph, onLinkClick: ((String) -> Unit)?) {
    val annotated = parseInlineMarkdown(block.text)
    Text(
        text = annotated,
        color = TerminalWhite,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BulletListBlock(block: MarkdownBlock.BulletList, onLinkClick: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        block.items.forEachIndexed { idx, item ->
            val indent = block.indentLevels.getOrNull(idx) ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (indent * 16).dp, bottom = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp, end = 8.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Accent)
                )
                Text(
                    text = parseInlineMarkdown(item),
                    color = TerminalWhite,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun NumberedListBlock(block: MarkdownBlock.NumberedList, onLinkClick: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        block.items.forEachIndexed { idx, item ->
            val num = block.numbers.getOrNull(idx) ?: (idx + 1)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "$num.",
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = parseInlineMarkdown(item),
                    color = TerminalWhite,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ChecklistBlock(block: MarkdownBlock.Checklist, onLinkClick: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        block.items.forEach { (checked, text) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp, end = 8.dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (checked) Success else SurfaceLight)
                        .border(1.dp, if (checked) Success else TerminalWhite.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (checked) {
                        Text("✓", color = ChatBg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = parseInlineMarkdown(text),
                    color = if (checked) TerminalWhite.copy(alpha = 0.6f) else TerminalWhite,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CodeBlockBlock(block: MarkdownBlock.CodeBlock) {
    val clipboard = LocalClipboardManager.current
    var showCopied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ChatBg.copy(alpha = 0.6f))
            .border(1.dp, SurfaceLight, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceLight.copy(alpha = 0.3f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language.ifBlank { "code" },
                color = Accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(block.code))
                    showCopied = true
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (showCopied) Success else TerminalWhite.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (showCopied) "Copied" else "Copy",
                    color = if (showCopied) Success else TerminalWhite.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = block.code,
            color = TerminalWhite,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        )
    }
}

@Composable
private fun QuoteBlock(block: MarkdownBlock.Quote, onLinkClick: ((String) -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceLight.copy(alpha = 0.15f))
            .border(width = 3.dp, color = TerminalWhite.copy(alpha = 0.3f), shape = RoundedCornerShape(4.dp))
            .padding(10.dp)
    ) {
        block.lines.forEach { line ->
            Text(
                text = parseInlineMarkdown(line),
                color = TerminalWhite.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TableBlock(block: MarkdownBlock.Table) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, SurfaceLight, RoundedCornerShape(8.dp))
            .horizontalScroll(scrollState)
    ) {
        Row(
            modifier = Modifier
                .background(Accent.copy(alpha = 0.15f))
                .padding(vertical = 6.dp)
        ) {
            block.headers.forEach { header ->
                Text(
                    text = header,
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .widthIn(min = 80.dp)
                        .padding(horizontal = 10.dp)
                )
            }
        }
        block.rows.forEach { row ->
            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
            ) {
                row.forEach { cell ->
                    Text(
                        text = parseInlineMarkdown(cell),
                        color = TerminalWhite,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .widthIn(min = 80.dp)
                            .padding(horizontal = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalloutBlock(block: MarkdownBlock.Callout, onLinkClick: ((String) -> Unit)?) {
    // COMPILE FIX: explicitly type the Quad so Kotlin can resolve the Color
    // overloads for background() and border(). Without this annotation the
    // compiler sees Quad<out Color, out Color, out String, out Color> and
    // cannot pick between background(Brush) and background(Color).
    val quad: Quad<Color, Color, String, Color> = when (block.type) {
        CalloutType.INFO    -> Quad(SurfaceLight.copy(alpha = 0.2f), Accent, "ℹ", Accent)
        CalloutType.SUCCESS -> Quad(Success.copy(alpha = 0.15f), Success, "✓", Success)
        CalloutType.WARNING -> Quad(TerminalYellow.copy(alpha = 0.15f), TerminalYellow, "⚠", TerminalYellow)
        CalloutType.DANGER  -> Quad(Danger.copy(alpha = 0.15f), Danger, "🔴", Danger)
        CalloutType.QUOTE   -> Quad(SurfaceLight.copy(alpha = 0.1f), TerminalWhite.copy(alpha = 0.3f), "›", TerminalWhite.copy(alpha = 0.7f))
    }
    val bgColor: Color = quad.a
    val borderColor: Color = quad.b
    val icon: String = quad.c
    val labelColor: Color = quad.d
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = icon,
            color = labelColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            block.text.split("\n").forEach { line ->
                // COMPILE FIX: explicitly type as AnnotatedString so Kotlin
                // picks the Text(AnnotatedString, ...) overload instead of
                // being ambiguous between Text(String) and Text(AnnotatedString).
                val annotated: AnnotatedString = parseInlineMarkdown(line)
                Text(
                    text = annotated,
                    color = TerminalWhite,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HorizontalRuleBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(TerminalWhite.copy(alpha = 0.2f))
    )
}

// Helper for destructuring 4 values (Kotlin doesn't have Quad in stdlib).
// COMPILE FIX: data class auto-generates componentN() operators — do NOT add
// manual operator fun component1() etc. (causes "Conflicting overloads").
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
