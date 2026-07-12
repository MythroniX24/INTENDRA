package com.interndra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ai.tasks.TaskPlan
import com.interndra.ai.tasks.TaskStatus
import com.interndra.ai.tasks.StepStatus
import com.interndra.ai.tasks.TaskStep
import com.interndra.ui.theme.*
import kotlinx.coroutines.delay

// ── Keep backward-compat colors reference ────────────────────────────────
@Composable
private fun themeColors() = LocalInterndraColors.current

// ═══════════════════════════════════════════════════════════════════════════════
// ThinkingIndicator — Animated dots with cycling status text
// ═══════════════════════════════════════════════════════════════════════════════

private val thinkingPhrases = listOf(
    "Thinking", "Analyzing", "Processing", "Reasoning", "Researching",
    "Computing", "Evaluating", "Considering", "Planning", "Generating"
)

/**
 * Animated thinking indicator with pulsing dots and cycling status text.
 * Shows: "● ● ● Thinking" with animated opacity on dots and rotating text.
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
    dotsColor: Color = Accent,
    textColor: Color = TerminalWhite.copy(alpha = 0.5f)
) {
    var phraseIndex by remember { mutableStateOf(0) }

    // Cycle through phrases every 2 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(2200)
            phraseIndex = (phraseIndex + 1) % thinkingPhrases.size
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "thinking")

    // Pulse animation for each dot (staggered by 300ms)
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(600, 200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(600, 400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "dot3"
    )

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Three animated dots
        Box(Modifier.size(6.dp).alpha(dot1Alpha).clip(CircleShape).background(dotsColor))
        Box(Modifier.size(6.dp).alpha(dot2Alpha).clip(CircleShape).background(dotsColor))
        Box(Modifier.size(6.dp).alpha(dot3Alpha).clip(CircleShape).background(dotsColor))

        Spacer(Modifier.width(8.dp))

        // Cycling phrase
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400))
        ) {
            Text(
                thinkingPhrases[phraseIndex],
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MessageActionsBar — Copy, Regenerate, Share actions below messages
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Action bar displayed below AI messages for quick actions.
 * Supports: Copy, Regenerate, Thumbs Up/Down (visual only), Share.
 */
@Composable
fun MessageActionsBar(
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    isUserMessage: Boolean = false
) {
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Copy button
        ActionButton(
            icon = Icons.Default.ContentCopy,
            label = if (copied) "Copied!" else "Copy",
            tint = if (copied) Success else TerminalWhite.copy(alpha = 0.35f),
            onClick = {
                copied = true
                onCopy()
            }
        )

        // Regenerate (AI messages only)
        if (!isUserMessage && onRegenerate != null) {
            ActionButton(
                icon = Icons.Default.Refresh,
                label = "Regenerate",
                tint = TerminalWhite.copy(alpha = 0.35f),
                onClick = onRegenerate
            )
        }

        // Share
        if (onShare != null) {
            ActionButton(
                icon = Icons.Default.Share,
                label = "Share",
                tint = TerminalWhite.copy(alpha = 0.35f),
                onClick = onShare
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(14.dp))
            Text(label, color = tint, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// StatusBadge — Colored status indicator for AI responses
// ═══════════════════════════════════════════════════════════════════════════════

enum class StatusType { SUCCESS, WARNING, ERROR, INFO, PROCESSING }

@Composable
fun StatusBadge(
    type: StatusType,
    text: String,
    modifier: Modifier = Modifier
) {
    val (bg, border, icon, labelColor) = when (type) {
        StatusType.SUCCESS -> listOf(Success.copy(alpha = 0.1f), Success.copy(alpha = 0.3f), "✓", Success)
        StatusType.WARNING -> listOf(TerminalYellow.copy(alpha = 0.1f), TerminalYellow.copy(alpha = 0.3f), "⚠", TerminalYellow)
        StatusType.ERROR -> listOf(Danger.copy(alpha = 0.1f), Danger.copy(alpha = 0.3f), "✗", Danger)
        StatusType.INFO -> listOf(Accent.copy(alpha = 0.1f), Accent.copy(alpha = 0.3f), "ℹ", Accent)
        StatusType.PROCESSING -> listOf(VaultPurple.copy(alpha = 0.1f), VaultPurple.copy(alpha = 0.3f), "⟳", VaultPurple)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = bg as Color,
        border = BorderStroke(1.dp, border as Color)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon as String, fontSize = 11.sp)
            Text(text, color = labelColor as Color, fontSize = 11.sp,
                fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// JsonTreeViewer — Collapsible JSON/XML viewer
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Renders JSON data as a collapsible tree with syntax coloring.
 * Each key/value level is indented and collapsible.
 */
@Composable
fun JsonTreeViewer(
    jsonString: String,
    modifier: Modifier = Modifier,
    maxDepth: Int = 5
) {
    val parsed = remember(jsonString) {
        try {
            parseJsonToTree(jsonString, maxDepth)
        } catch (_: Exception) {
            listOf(JsonNode.Value("⚠ Invalid JSON: ${jsonString.take(100)}", TerminalRed))
        }
    }

    val colors = LocalInterndraColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBlockBg)
            .border(1.dp, colors.codeBlockBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null,
                    tint = colors.terminalYellow, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("JSON", color = colors.terminalYellow, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            val clip = LocalClipboardManager.current
            Text("Copy", color = colors.terminalWhite.copy(0.4f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable {
                    clip.setText(AnnotatedString(jsonString))
                })
        }
        parsed.forEach { node ->
            JsonNodeRow(node, indent = 0)
        }
    }
}

private sealed class JsonNode {
    data class KeyValue(val key: String, val value: JsonNode) : JsonNode()
    data class Object(val key: String?, val children: List<JsonNode>) : JsonNode()
    data class Array(val key: String?, val children: List<JsonNode>) : JsonNode()
    data class Value(val text: String, val color: Color = TerminalWhite.copy(0.8f)) : JsonNode()
}

private fun parseJsonToTree(json: String, maxDepth: Int, depth: Int = 0): List<JsonNode> {
    if (depth >= maxDepth) return listOf(JsonNode.Value("…truncated", TerminalWhite.copy(0.4f)))
    val trimmed = json.trim()
    return when {
        trimmed.startsWith("{") -> parseObject(trimmed, maxDepth, depth)
        trimmed.startsWith("[") -> parseArray(trimmed, maxDepth, depth)
        else -> listOf(parseValue(trimmed))
    }
}

private fun parseObject(json: String, maxDepth: Int, depth: Int): List<JsonNode> {
    val content = json.removePrefix("{").removeSuffix("}").trim()
    if (content.isEmpty()) return listOf(JsonNode.Value("{ }", TerminalWhite.copy(0.4f)))
    val nodes = mutableListOf<JsonNode>()
    var i = 0; var depth_ = 0; val current = StringBuilder(); var inString = false
    val pairs = mutableListOf<String>()
    while (i < content.length) {
        val ch = content[i]
        if (ch == '"' && (i == 0 || content[i - 1] != '\\')) inString = !inString
        if (!inString) {
            when (ch) {
                '{', '[' -> depth_++
                '}', ']' -> depth_--
                ',' -> if (depth_ == 0) { pairs.add(current.toString().trim()); current.clear(); i++; continue }
            }
        }
        current.append(ch); i++
    }
    if (current.isNotBlank()) pairs.add(current.toString().trim())

    for (pair in pairs) {
        val colonIdx = findFirstColonOutsideQuotes(pair)
        if (colonIdx > 0) {
            val key = pair.substring(0, colonIdx).trim().removeSurrounding("\"")
            val value = pair.substring(colonIdx + 1).trim()
            nodes.add(JsonNode.KeyValue(key, parseJsonValue(value, maxDepth, depth)))
        } else if (pair.startsWith("\"") && pair.endsWith("\"")) {
            nodes.add(JsonNode.Value(pair, TerminalGreen))
        } else {
            nodes.add(JsonNode.Value(pair, TerminalWhite.copy(0.7f)))
        }
    }
    return nodes
}

private fun parseArray(json: String, maxDepth: Int, depth: Int): List<JsonNode> {
    val content = json.removePrefix("[").removeSuffix("]").trim()
    if (content.isEmpty()) return listOf(JsonNode.Value("[ ]", TerminalWhite.copy(0.4f)))
    var i = 0; var depth_ = 0; val current = StringBuilder(); var inString = false
    val items = mutableListOf<String>()
    while (i < content.length) {
        val ch = content[i]
        if (ch == '"' && (i == 0 || content[i - 1] != '\\')) inString = !inString
        if (!inString) {
            when (ch) {
                '{', '[' -> depth_++
                '}', ']' -> depth_--
                ',' -> if (depth_ == 0) { items.add(current.toString().trim()); current.clear(); i++; continue }
            }
        }
        current.append(ch); i++
    }
    if (current.isNotBlank()) items.add(current.toString().trim())
    return items.mapIndexed { idx, item ->
        JsonNode.KeyValue("[$idx]", parseJsonValue(item, maxDepth, depth))
    }
}

private fun parseJsonValue(value: String, maxDepth: Int, depth: Int): JsonNode {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("{") -> JsonNode.Object(null, parseJsonToTree(trimmed, maxDepth, depth + 1))
        trimmed.startsWith("[") -> JsonNode.Array(null, parseJsonToTree(trimmed, maxDepth, depth + 1))
        trimmed == "true" -> JsonNode.Value("true", TerminalGreen)
        trimmed == "false" -> JsonNode.Value("false", TerminalRed)
        trimmed == "null" -> JsonNode.Value("null", TerminalWhite.copy(0.4f))
        trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
            JsonNode.Value(trimmed, TerminalGreen)
        trimmed.toDoubleOrNull() != null -> JsonNode.Value(trimmed, VaultCyan)
        else -> JsonNode.Value(trimmed, TerminalWhite.copy(0.8f))
    }
}

private fun parseValue(trimmed: String): JsonNode = parseJsonValue(trimmed, 5, 0)

private fun findFirstColonOutsideQuotes(s: String): Int {
    var inStr = false
    s.forEachIndexed { idx, ch ->
        if (ch == '"' && (idx == 0 || s[idx - 1] != '\\')) inStr = !inStr
        if (!inStr && ch == ':') return idx
    }
    return -1
}

@Composable
private fun JsonNodeRow(node: JsonNode, indent: Int) {
    when (node) {
        is JsonNode.Value -> {
            Row(Modifier.fillMaxWidth().padding(start = (indent * 16).dp, top = 1.dp, bottom = 1.dp)) {
                Text(node.text, color = node.color, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        is JsonNode.KeyValue -> {
            var expanded by remember { mutableStateOf(false) }
            val isExpandable = node.value is JsonNode.Object || node.value is JsonNode.Array

            Row(
                Modifier.fillMaxWidth().padding(start = (indent * 16).dp, top = 1.dp, bottom = 1.dp)
                    .then(if (isExpandable) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.Top
            ) {
                if (isExpandable) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight,
                        null, tint = TerminalWhite.copy(0.4f), modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                } else {
                    Spacer(Modifier.width(16.dp))
                }
                Text("\"${node.key}\"", color = Accent, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text(": ", color = TerminalWhite.copy(0.6f), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                when (val v = node.value) {
                    is JsonNode.Value ->
                        Text(v.text, color = v.color, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    is JsonNode.Object ->
                        if (!expanded) Text("{…}", color = TerminalWhite.copy(0.4f),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    is JsonNode.Array ->
                        if (!expanded) Text("[…]", color = TerminalWhite.copy(0.4f),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    is JsonNode.KeyValue -> {}
                }
            }

            if (expanded) {
                when (val v = node.value) {
                    is JsonNode.Object -> v.children.forEach { JsonNodeRow(it, indent + 1) }
                    is JsonNode.Array -> v.children.forEach { JsonNodeRow(it, indent + 1) }
                    is JsonNode.Value -> {}
                    is JsonNode.KeyValue -> {}
                }
            }
        }
        is JsonNode.Object -> node.children.forEach { JsonNodeRow(it, indent) }
        is JsonNode.Array -> node.children.forEach { JsonNodeRow(it, indent) }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ExpandableCodeBlock — Code block with expand/collapse for long content
// ═══════════════════════════════════════════════════════════════════════════════

/** Max lines before code block is collapsed by default */
private const val MAX_VISIBLE_LINES = 20

/**
 * Enhanced code block with syntax-highlighted keywords, line numbers,
 * copy button, language badge, and expand/collapse for long blocks.
 */
@Composable
fun ExpandableCodeBlock(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier
) {
    val lines = remember(code) { code.split("\n") }
    val isLong = lines.size > MAX_VISIBLE_LINES
    var expanded by remember { mutableStateOf(!isLong) }
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val colors = LocalInterndraColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBlockBg)
            .border(1.dp, colors.codeBlockBorder, RoundedCornerShape(10.dp))
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth()
                .background(colors.codeBlockHeader)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val langColor = getLangColor(language)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(langColor))
                Spacer(Modifier.width(8.dp))
                Text(
                    language.ifBlank { "code" },
                    color = langColor, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
                )
                if (lines.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${lines.size} lines",
                        color = TerminalWhite.copy(0.35f), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLong) {
                    Text(
                        if (expanded) "Collapse" else "Expand",
                        color = TerminalWhite.copy(0.4f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
                val copyColor = if (copied) Success else TerminalWhite.copy(0.5f)
                Row(
                    Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null,
                        tint = copyColor, modifier = Modifier.size(14.dp))
                    Text(if (copied) "Copied!" else "Copy",
                        color = copyColor, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Code content
        val displayLines = if (expanded || !isLong) lines else lines.take(MAX_VISIBLE_LINES)

        Box(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            Column {
                displayLines.forEachIndexed { idx, line ->
                    val highlighted = remember(line, language) {
                        if (language.isNotBlank()) highlightSyntax(line, language) else null
                    }
                    Row(Modifier.fillMaxWidth()) {
                        // Line number
                        Text(
                            "${idx + 1}",
                            color = TerminalWhite.copy(0.2f), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                            modifier = Modifier.widthIn(min = 36.dp).padding(horizontal = 8.dp)
                        )
                        // Code line
                        if (highlighted != null) {
                            ClickableText(
                                text = highlighted,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp),
                                onClick = {}
                            )
                        } else {
                            Text(
                                line,
                                color = TerminalWhite.copy(0.9f), fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                if (!expanded && isLong) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "▼ Show all ${lines.size} lines",
                            color = Accent.copy(0.6f), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Syntax highlighting helpers
// ═══════════════════════════════════════════════════════════════════════════════

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "enum", "data", "sealed",
    "if", "else", "when", "for", "while", "do", "return", "break", "continue",
    "try", "catch", "finally", "throw", "import", "package", "typealias",
    "suspend", "override", "private", "public", "protected", "internal",
    "const", "lateinit", "by", "in", "out", "where", "is", "as", "null",
    "true", "false", "this", "super", "it", "companion", "object",
    // Java
    "public", "static", "void", "int", "String", "boolean", "long", "double",
    "float", "char", "byte", "short", "new", "extends", "implements", "abstract",
    "final", "synchronized", "volatile", "transient", "instanceof",
    // Python
    "def", "lambda", "yield", "with", "elif", "not", "and", "or", "pass",
    "None", "True", "False", "self", "print", "range", "len", "type",
    // JS/TS
    "function", "let", "const", "var", "async", "await", "export", "default",
    "undefined", "NaN", "typeof", "instanceof", "interface", "type", "enum",
    // Shell
    "echo", "cd", "ls", "pwd", "mkdir", "rm", "cp", "mv", "grep", "find",
    "export", "source", "chmod", "chown", "sudo", "exit", "return",
    // SQL
    "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP",
    "ALTER", "TABLE", "INTO", "VALUES", "SET", "JOIN", "LEFT", "RIGHT", "INNER",
    "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS", "ON", "AND", "OR",
    "NOT", "NULL", "IS", "LIKE", "IN", "BETWEEN", "COUNT", "SUM", "AVG", "MAX", "MIN"
)

/** Basic syntax highlighting for common languages */
private fun highlightSyntax(line: String, language: String): AnnotatedString {
    val lang = language.lowercase()
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            // String literals
            if (line[i] == '"' || line[i] == '\'') {
                val quote = line[i]
                val end = line.indexOf(quote, i + 1)
                if (end > i) {
                    withStyle(SpanStyle(color = TerminalGreen)) {
                        append(line.substring(i, end + 1))
                    }
                    i = end + 1
                    continue
                }
            }
            // Single-line comments
            if ((lang in listOf("kt", "kotlin", "java", "js", "ts", "javascript", "typescript", "go", "rust", "rs", "cpp", "c++", "c", "swift")) &&
                line.substring(i).startsWith("//")) {
                withStyle(SpanStyle(color = TerminalWhite.copy(0.4f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(line.substring(i))
                }
                return@buildAnnotatedString
            }
            if ((lang in listOf("py", "python", "sh", "bash", "shell", "rb", "ruby", "pl", "perl")) &&
                line.substring(i).startsWith("#")) {
                withStyle(SpanStyle(color = TerminalWhite.copy(0.4f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(line.substring(i))
                }
                return@buildAnnotatedString
            }
            // SQL comment
            if (lang == "sql" && line.substring(i, minOf(i + 2, line.length)).startsWith("--")) {
                withStyle(SpanStyle(color = TerminalWhite.copy(0.4f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(line.substring(i))
                }
                return@buildAnnotatedString
            }

            // Check for keywords at word boundaries
            var matched = false
            for (kw in KEYWORDS) {
                if (line.regionMatches(i, kw, 0, kw.length, ignoreCase = true)) {
                    val afterIdx = i + kw.length
                    val beforeIdx = i - 1
                    val isWordStart = beforeIdx < 0 || !line[beforeIdx].isLetterOrDigit() && line[beforeIdx] != '_'
                    val isWordEnd = afterIdx >= line.length || !line[afterIdx].isLetterOrDigit() && line[afterIdx] != '_'
                    if (isWordStart && isWordEnd) {
                        withStyle(SpanStyle(color = VaultPurple, fontWeight = FontWeight.SemiBold)) {
                            append(kw)
                        }
                        i += kw.length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                // Numbers
                if (line[i].isDigit()) {
                    val start = i
                    while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
                    withStyle(SpanStyle(color = VaultCyan)) {
                        append(line.substring(start, i))
                    }
                } else {
                    append(line[i])
                    i++
                }
            }
        }
    }
}

private fun getLangColor(lang: String): Color = when (lang.lowercase()) {
    "kotlin", "kt", "java" -> Color(0xFF7F52FF)
    "python", "py" -> Color(0xFF3776AB)
    "javascript", "js" -> Color(0xFFF7DF1E)
    "typescript", "ts" -> Color(0xFF3178C6)
    "go" -> Color(0xFF00ADD8)
    "rust", "rs" -> Color(0xFFDEA584)
    "cpp", "c++", "c" -> Color(0xFF00599C)
    "swift" -> Color(0xFFF05138)
    "ruby", "rb" -> Color(0xFFCC342D)
    "php" -> Color(0xFF777BB4)
    "shell", "bash", "sh", "zsh" -> Color(0xFF4EAA25)
    "sql" -> Color(0xFFE38C00)
    "html", "xml", "svg" -> Color(0xFFE34F26)
    "css", "scss" -> Color(0xFF1572B6)
    "json", "yaml", "yml", "toml" -> Color(0xFF5B5B5B)
    "docker", "dockerfile" -> Color(0xFF2496ED)
    "diff" -> VaultPurple
    "mermaid" -> VaultCyan
    else -> Accent
}

// ═══════════════════════════════════════════════════════════════════════════════
// TaskCard — Claude-like task execution card with progress & step tracking
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Renders a task execution card showing title, progress bar, step list
 * with animated status indicators, and action buttons (pause/resume/retry/cancel).
 */
@Composable
fun TaskCard(
    task: TaskPlan,
    modifier: Modifier = Modifier,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetryStep: ((Int) -> Unit)? = null,
    expanded: Boolean = true
) {
    var isExpanded by remember { mutableStateOf(expanded) }

    val statusColor = when (task.status) {
        TaskStatus.RUNNING -> Accent
        TaskStatus.COMPLETED -> Success
        TaskStatus.FAILED -> Danger
        TaskStatus.CANCELLED -> TerminalYellow
        TaskStatus.PAUSED -> TerminalYellow
        TaskStatus.PLANNED -> TerminalWhite.copy(0.5f)
    }

    val statusIcon = when (task.status) {
        TaskStatus.RUNNING -> "⚡"
        TaskStatus.COMPLETED -> "✅"
        TaskStatus.FAILED -> "❌"
        TaskStatus.CANCELLED -> "⏹"
        TaskStatus.PAUSED -> "⏸"
        TaskStatus.PLANNED -> "📋"
    }

    val statusLabel = when (task.status) {
        TaskStatus.RUNNING -> "Running"
        TaskStatus.COMPLETED -> "Completed"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.CANCELLED -> "Cancelled"
        TaskStatus.PAUSED -> "Paused"
        TaskStatus.PLANNED -> "Planned"
    }

    val colors = LocalInterndraColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceCard)
            .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    ) {
        // Header with title + status
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .background(statusColor.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(statusIcon, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        task.title,
                        color = TerminalWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (task.description.isNotBlank()) {
                        Text(
                            task.description,
                            color = TerminalWhite.copy(0.5f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Status badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Pulsing dot for running
                    if (task.status == TaskStatus.RUNNING) {
                        val inf = rememberInfiniteTransition(label = "task_pulse")
                        val alpha by inf.animateFloat(0.4f, 1f,
                            infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
                        Box(
                            Modifier.size(6.dp).alpha(alpha)
                                .clip(CircleShape).background(statusColor)
                        )
                    }
                    Text(statusLabel, color = statusColor, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.width(4.dp))
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = TerminalWhite.copy(0.4f), modifier = Modifier.size(18.dp)
            )
        }

        // Progress bar
        if (task.steps.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(3.dp).background(SurfaceLight.copy(alpha = 0.3f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(
                            when (task.status) {
                                TaskStatus.COMPLETED -> Success
                                TaskStatus.FAILED -> Danger
                                else -> Accent
                            }
                        )
                )
            }
            // Step counter
            Row(
                Modifier.fillMaxWidth()
                    .background(statusColor.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${task.completedSteps}/${task.steps.size} steps",
                    color = TerminalWhite.copy(0.4f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (task.totalDurationMs > 0) {
                    Text(
                        formatDuration(task.totalDurationMs),
                        color = TerminalWhite.copy(0.3f), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Step list (expanded)
        AnimatedVisibility(visible = isExpanded) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                task.steps.forEach { step ->
                    TaskStepRow(
                        step = step,
                        isExecuting = task.status == TaskStatus.RUNNING,
                        onRetry = if (step.status == StepStatus.FAILED && onRetryStep != null) {
                            { onRetryStep(step.index) }
                        } else null
                    )
                }
            }
        }

        // Action buttons (bottom)
        if (task.status != TaskStatus.COMPLETED && task.status != TaskStatus.PLANNED) {
            Divider(color = SurfaceLight.copy(alpha = 0.2f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pause / Resume
                when (task.status) {
                    TaskStatus.RUNNING -> {
                        TaskActionChip("⏸ Pause", TerminalYellow, onPause)
                    }
                    TaskStatus.PAUSED -> {
                        TaskActionChip("▶ Resume", Success, onResume)
                    }
                    else -> {}
                }

                // Retry (failed/cancelled)
                if (task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED) {
                    TaskActionChip("↻ Retry All", Accent, onRetry)
                }

                Spacer(Modifier.weight(1f))

                // Cancel (only if running or paused)
                if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PAUSED) {
                    TaskActionChip("✕ Cancel", Danger, onCancel)
                }
            }
        }
    }
}

@Composable
private fun TaskStepRow(
    step: TaskStep,
    isExecuting: Boolean,
    onRetry: (() -> Unit)?
) {
    val statusIcon = when (step.status) {
        StepStatus.COMPLETED -> "✓"
        StepStatus.RUNNING -> "●"
        StepStatus.FAILED -> "✗"
        StepStatus.PENDING -> "○"
        StepStatus.SKIPPED -> "⏭"
    }

    val statusColor = when (step.status) {
        StepStatus.COMPLETED -> Success
        StepStatus.RUNNING -> Accent
        StepStatus.FAILED -> Danger
        StepStatus.PENDING -> TerminalWhite.copy(0.3f)
        StepStatus.SKIPPED -> TerminalWhite.copy(0.2f)
    }

    val bgColor = when {
        step.status == StepStatus.RUNNING -> Accent.copy(alpha = 0.06f)
        step.status == StepStatus.FAILED -> Danger.copy(alpha = 0.04f)
        step.index % 2 == 1 -> Color.Transparent
        else -> SurfaceLight.copy(alpha = 0.04f)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (step.status == StepStatus.RUNNING && isExecuting) Color.Transparent
                    else statusColor.copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (step.status == StepStatus.RUNNING && isExecuting) {
                val inf = rememberInfiniteTransition(label = "step_pulse_${step.index}")
                val alpha by inf.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
                Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(statusColor))
            } else {
                Text(statusIcon, fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.width(8.dp))

        // Step label
        Column(Modifier.weight(1f)) {
            Text(
                step.label,
                color = if (step.status == StepStatus.PENDING) TerminalWhite.copy(0.5f) else TerminalWhite,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (step.status == StepStatus.SKIPPED)
                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                else androidx.compose.ui.text.style.TextDecoration.None
            )
            if (step.error != null && step.status == StepStatus.FAILED) {
                Text(
                    step.error.take(80),
                    color = Danger.copy(0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Duration or retry
        if (step.durationMs > 0 && step.status != StepStatus.RUNNING) {
            Text(
                formatDuration(step.durationMs),
                color = TerminalWhite.copy(0.3f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Retry button for failed steps
        if (step.status == StepStatus.FAILED && onRetry != null) {
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Danger.copy(alpha = 0.1f),
                modifier = Modifier.clickable(onClick = onRetry)
            ) {
                Text("Retry", color = Danger, fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun TaskActionChip(
    label: String,
    color: Color,
    onClick: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}
