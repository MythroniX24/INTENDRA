package com.interndra.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ui.theme.InterndraColors
import com.interndra.ui.theme.LocalInterndraColors

/**
 * MarkdownText — an offline, Compose-native Markdown renderer for AI replies.
 *
 * Supports document-style rendering: headings, paragraphs, bold/italic/
 * strikethrough, inline code, fenced code blocks with a language label + copy
 * button, bullet/numbered lists (with indentation), blockquotes, horizontal
 * rules, tables, links and LaTeX math (Unicode). Tolerant of unclosed markers
 * while streaming — it never crashes.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    isStreaming: Boolean = false
) {
    val colors = LocalInterndraColors.current
    val blocks = remember(markdown, isStreaming) { MarkdownParser.parse(markdown, isStreaming) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownParser.Block.Heading -> HeadingText(block, style, colors)
                is MarkdownParser.Block.Paragraph -> ParagraphText(block, style, colors)
                is MarkdownParser.Block.Code -> CodeBlock(block, colors)
                is MarkdownParser.Block.Quote -> QuoteBlock(block, style, colors)
                is MarkdownParser.Block.Rule -> Rule(colors)
                is MarkdownParser.Block.LatexBlock -> LatexBlockText(block)
                is MarkdownParser.Block.Table -> TableBlock(block, style, colors)
                is MarkdownParser.Block.ListBlock -> ListBlock(block, style, colors)
            }
        }
    }
}

// ── Block rendering ─────────────────────────────────────────────────────────

@Composable
private fun HeadingText(block: MarkdownParser.Block.Heading, base: TextStyle, colors: InterndraColors) {
    val sizes = listOf(24, 21, 19, 17, 16, 15)
    val level = block.level.coerceIn(1, 6)
    Text(
        text = buildInline(block.text, colors),
        fontSize = sizes[level - 1].sp,
        fontWeight = FontWeight.Bold,
        color = base.color.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.onBackground,
        lineHeight = (sizes[level - 1] + 6).sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (level == 1) 12.dp else 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun ParagraphText(block: MarkdownParser.Block.Paragraph, base: TextStyle, colors: InterndraColors) {
    Text(
        text = buildInline(block.text, colors),
        style = base.copy(
            color = base.color.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.onBackground
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}

@Composable
private fun CodeBlock(block: MarkdownParser.Block.Code, colors: InterndraColors) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.codeBlockBg,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (copied) "Copied ✓" else "Copy",
                    color = if (copied) Color(0xFF1E8E3E) else colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                text = block.code,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun QuoteBlock(block: MarkdownParser.Block.Quote, base: TextStyle, colors: InterndraColors) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .height(36.dp)
                .background(colors.accent.copy(alpha = 0.35f))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = buildInline(block.text, colors),
            style = base.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Rule(colors: InterndraColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun LatexBlockText(block: MarkdownParser.Block.LatexBlock) {
    Text(
        text = LatexMath.toUnicode(block.formula),
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

@Composable
private fun ListBlock(block: MarkdownParser.Block.ListBlock, base: TextStyle, colors: InterndraColors) {
    val counters = mutableMapOf<Int, Int>()
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        block.items.forEach { item ->
            val depth = item.depth
            val indent = (depth * 16).dp
            val marker = if (item.ordered) {
                val n = (counters[depth] ?: 0) + 1
                counters[depth] = n
                "$n."
            } else {
                when (depth % 3) {
                    0 -> "•"
                    1 -> "◦"
                    else -> "▪"
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = indent, bottom = 3.dp)) {
                Text(
                    text = marker,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = base.fontSize,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(18.dp)
                )
                Text(
                    text = buildInline(item.text, colors),
                    style = base.copy(
                        color = base.color.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableBlock(block: MarkdownParser.Block.Table, base: TextStyle, colors: InterndraColors) {
    val rows = listOf(block.headers) + block.rows
    val colCount = block.headers.size
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceCard,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { r, cells ->
                val isHeader = r == 0
                Row(
                    modifier = Modifier
                        .background(if (isHeader) colors.inputFieldBg else Color.Transparent)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(colCount) { c ->
                        val cell = cells.getOrNull(c) ?: ""
                        val align = block.alignments.getOrNull(c) ?: MarkdownParser.TableAlign.LEFT
                        Row(
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            horizontalArrangement = when (align) {
                                MarkdownParser.TableAlign.RIGHT -> Arrangement.End
                                MarkdownParser.TableAlign.CENTER -> Arrangement.Center
                                else -> Arrangement.Start
                            }
                        ) {
                            Text(
                                text = buildInline(cell, colors),
                                style = base.copy(
                                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = if (isHeader) base.fontSize else (base.fontSize.value - 1).sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            )
                        }
                    }
                }
                if (isHeader) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
    }
}

// ── Inline Markdown → AnnotatedString ───────────────────────────────────────

internal fun buildInline(text: String, colors: InterndraColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]

            // Escape: \X → X
            if (c == '\\' && i + 1 < n) {
                append(text[i + 1])
                i += 2
                continue
            }

            // Inline code
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    val code = text.substring(i + 1, end)
                    withStyle(
                        SpanStyle(
                            background = colors.inlineCodeBg,
                            color = colors.inlineCodeText,
                            fontFamily = FontFamily.Monospace
                        )
                    ) { append(code) }
                    i = end + 1
                    continue
                }
                append(c); i++; continue
            }

            // Bold + italic ***
            if (i + 2 < n && text.startsWith("***", i)) {
                val end = text.indexOf("***", i + 3)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(buildInline(text.substring(i + 3, end), colors))
                    }
                    i = end + 3
                    continue
                }
            }

            // Bold ** or __
            if (i + 1 < n && (text.startsWith("**", i) || text.startsWith("__", i))) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(buildInline(text.substring(i + 2, end), colors))
                    }
                    i = end + 2
                    continue
                }
            }

            // Strikethrough ~~
            if (i + 1 < n && text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(buildInline(text.substring(i + 2, end), colors))
                    }
                    i = end + 2
                    continue
                }
            }

            // Italic * or _ (single)
            if (c == '*' || c == '_') {
                val end = text.indexOf(c, i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(buildInline(text.substring(i + 1, end), colors))
                    }
                    i = end + 1
                    continue
                }
            }

            // Link [label](url)
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                if (close > i) {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, urlEnd)
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(
                                SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)
                            ) { append(label) }
                        }
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            // Inline LaTeX $...$
            if (c == '$') {
                val end = text.indexOf('$', i + 1)
                if (end > i) {
                    val latex = LatexMath.toUnicode(text.substring(i + 1, end))
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(latex) }
                    i = end + 1
                    continue
                }
            }

            append(c)
            i++
        }
    }
}

// ── Parser ──────────────────────────────────────────────────────────────────

object MarkdownParser {

    enum class TableAlign { LEFT, CENTER, RIGHT }

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        data class Code(val language: String, val code: String) : Block()
        data class Quote(val text: String) : Block()
        object Rule : Block()
        data class LatexBlock(val formula: String) : Block()
        data class Table(val headers: List<String>, val alignments: List<TableAlign>, val rows: List<List<String>>) : Block()
        data class ListItem(val text: String, val ordered: Boolean, val depth: Int) : Block()
        data class ListBlock(val items: List<ListItem>) : Block()
    }

    fun parse(markdown: String, isStreaming: Boolean): List<Block> {
        if (markdown.isBlank()) return emptyList()
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            val fenceMatch = Regex("^\\s*(```+|~~~+)\\s*(\\S*)").find(line)
            if (fenceMatch != null) {
                val fence = fenceMatch.groupValues[1]
                val lang = fenceMatch.groupValues[2]
                val code = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    if (Regex("^\\s*$fence\\s*$").matches(lines[i])) { closed = true; i++; break }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks.add(Block.Code(lang, code.toString()))
                if (!closed && isStreaming) break
                continue
            }

            // Block LaTeX $$
            if (line.trimStart().startsWith("$$")) {
                val formula = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trim().endsWith("$$") || lines[i].trim() == "$$") {
                        formula.append(lines[i].trim().removeSuffix("$$"))
                        closed = true
                        i++
                        break
                    }
                    if (formula.isNotEmpty()) formula.append('\n')
                    formula.append(lines[i])
                    i++
                }
                blocks.add(Block.LatexBlock(formula.toString()))
                if (!closed && isStreaming) break
                continue
            }

            // Blank line
            if (line.isBlank()) { i++; continue }

            // Horizontal rule
            if (Regex("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$").matches(line)) {
                blocks.add(Block.Rule)
                i++
                continue
            }

            // Heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (heading != null) {
                blocks.add(Block.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
                i++
                continue
            }

            // Table
            if (line.contains('|') && i + 1 < lines.size && lines[i + 1].trimStart().startsWith('|')) {
                val separator = Regex("^\\s*\\|?\\s*(:?-+:?)\\s*(\\|\\s*(:?-+:?)\\s*)*\\|?\\s*$").matches(lines[i + 1])
                if (separator) {
                    val headers = splitTableRow(line)
                    val alignments = parseAlignments(lines[i + 1])
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].contains('|')) {
                        rows.add(splitTableRow(lines[i]))
                        i++
                    }
                    blocks.add(Block.Table(headers, alignments, rows))
                    continue
                }
            }

            // Blockquote
            if (line.trimStart().startsWith('>')) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith('>')) {
                    quoteLines.add(lines[i].trimStart().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(Block.Quote(quoteLines.joinToString(" ")))
                continue
            }

            // List
            val listMatch = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.*)$").find(line)
            if (listMatch != null) {
                val items = mutableListOf<Block.ListItem>()
                while (i < lines.size) {
                    val lm = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.*)$").find(lines[i]) ?: break
                    val depth = lm.groupValues[1].length / 2
                    val ordered = lm.groupValues[2].contains('.')
                    items.add(Block.ListItem(lm.groupValues[3].trim(), ordered, depth))
                    i++
                }
                blocks.add(Block.ListBlock(items))
                continue
            }

            // Paragraph
            val para = StringBuilder(line)
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (isBlockStart(l, lines, i)) break
                para.append('\n').append(l)
                i++
            }
            blocks.add(Block.Paragraph(para.toString()))
        }
        return blocks
    }

    private fun isBlockStart(line: String, lines: List<String>, index: Int): Boolean {
        if (line.isBlank()) return false
        val t = line.trimStart()
        if (Regex("^(#{1,6})\\s").containsMatchIn(t)) return true
        if (Regex("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$").matches(line)) return true
        if (t.startsWith("```") || t.startsWith("~~~")) return true
        if (t.startsWith("$$")) return true
        if (t.startsWith(">")) return true
        if (Regex("^(\\s*)([-*+]|\\d+\\.)\\s+").containsMatchIn(line)) return true
        if (line.contains('|') && index + 1 < lines.size && lines[index + 1].trimStart().startsWith('|')) return true
        return false
    }

    private fun splitTableRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.removePrefix("|")
        if (s.endsWith("|")) s = s.removeSuffix("|")
        return s.split("|").map { it.trim() }
    }

    private fun parseAlignments(separator: String): List<TableAlign> {
        var s = separator.trim()
        if (s.startsWith("|")) s = s.removePrefix("|")
        if (s.endsWith("|")) s = s.removeSuffix("|")
        return s.split("|").map { cell ->
            val c = cell.trim()
            val left = c.startsWith(":")
            val right = c.endsWith(":")
            when {
                left && right -> TableAlign.CENTER
                right -> TableAlign.RIGHT
                else -> TableAlign.LEFT
            }
        }
    }
}
