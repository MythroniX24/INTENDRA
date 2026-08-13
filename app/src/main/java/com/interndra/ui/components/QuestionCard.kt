package com.interndra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.AgentQuestion
import com.interndra.agent.QuestionAnswer
import com.interndra.agent.QuestionOption
import com.interndra.ui.theme.Accent
import com.interndra.ui.theme.LocalInterndraColors
import com.interndra.ui.theme.SurfaceCard
import com.interndra.ui.theme.SurfaceLight
import com.interndra.ui.theme.TerminalGreen
import com.interndra.ui.theme.TerminalRed
import com.interndra.ui.theme.TerminalWhite
import com.interndra.ui.theme.TerminalYellow

/**
 * QuestionCard — an inline chat card for the Agent's questioning engine.
 *
 * Questions appear as part of the conversation (spec §19) — never as blocking
 * dialogs. Options are large tappable rows (mobile-friendly touch targets,
 * spec §35). After the user answers, the card collapses to a compact
 * "✓ … selected" state (spec §20).
 *
 * Supports: single choice (with "Other" + "You decide"), multi choice,
 * confirmation, yes/no, text input, number input.
 */
@Composable
fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (QuestionAnswer) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalInterndraColors.current
    var answeredSummary by remember(question.id) { mutableStateOf<String?>(null) }

    if (answeredSummary != null) {
        // Collapsed state after answering (spec §20).
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.codeBlockBorder.copy(alpha = 0.4f))
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Check, null, tint = TerminalGreen, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    answeredSummary!!,
                    color = TerminalWhite.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.codeBlockBorder.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Help, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    question.question,
                    color = colors.inputTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))

            when (question) {
                is AgentQuestion.SingleChoice -> SingleChoiceBody(
                    question = question,
                    onAnswered = { answer, summary ->
                        answeredSummary = summary
                        onAnswer(answer)
                    }
                )

                is AgentQuestion.MultiChoice -> MultiChoiceBody(
                    question = question,
                    onAnswered = { answer, summary ->
                        answeredSummary = summary
                        onAnswer(answer)
                    }
                )

                is AgentQuestion.Confirmation -> ConfirmationBody(
                    question = question,
                    onAnswered = { answer, summary ->
                        answeredSummary = summary
                        onAnswer(answer)
                    }
                )

                is AgentQuestion.YesNo -> YesNoBody(
                    question = question,
                    onAnswered = { answer, summary ->
                        answeredSummary = summary
                        onAnswer(answer)
                    }
                )

                is AgentQuestion.TextInput -> TextInputBody(
                    question = question,
                    onAnswered = { answer, summary ->
                        answeredSummary = summary
                        onAnswer(answer)
                    }
                )

                is AgentQuestion.NumberInput -> NumberInputBody(
                    question = question,
                    onAnswered = { answer, summary ->
                        answeredSummary = summary
                        onAnswer(answer)
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            // Cancel task (spec §31)
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, TerminalRed.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Close, null, tint = TerminalRed, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cancel Task", color = TerminalRed, fontSize = 12.sp)
            }
        }
    }
}

// ── Single choice ──────────────────────────────────────────────────────────

@Composable
private fun SingleChoiceBody(
    question: AgentQuestion.SingleChoice,
    onAnswered: (QuestionAnswer, String) -> Unit
) {
    val colors = LocalInterndraColors.current
    var selected by remember(question.id) { mutableStateOf<String?>(null) }
    var showCustom by remember(question.id) { mutableStateOf(false) }
    var customText by remember(question.id) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        question.options.forEach { option ->
            OptionRow(
                option = option,
                selected = selected == option.id,
                onClick = {
                    selected = option.id
                    showCustom = false
                }
            )
        }
        if (question.youDecide) {
            OptionRow(
                option = QuestionOption("__you_decide__", "You decide", "Let the Agent choose the best option"),
                selected = selected == "__you_decide__",
                onClick = { selected = "__you_decide__" }
            )
        }
        if (question.allowCustomAnswer) {
            OptionRow(
                option = QuestionOption("__other__", "Other", "Type your own answer"),
                selected = showCustom,
                onClick = { showCustom = !showCustom; selected = null }
            )
        }

        AnimatedVisibility(visible = showCustom) {
            Column {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    placeholder = { Text("Type your answer…", color = TerminalWhite.copy(0.4f), fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                        focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        val trimmed = customText.trim()
                        if (trimmed.isNotEmpty()) {
                            onAnswered(QuestionAnswer.CustomAnswer(question.id, trimmed), "\u201C$trimmed\u201D")
                        }
                    },
                    enabled = customText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Continue", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(2.dp))
        Button(
            onClick = {
                when (selected) {
                    "__you_decide__" -> onAnswered(QuestionAnswer.YouDecide(question.id), "You decide")
                    null -> { /* nothing selected */ }
                    else -> {
                        val opt = question.options.firstOrNull { it.id == selected }
                        onAnswered(
                            QuestionAnswer.SingleChoiceAnswer(question.id, selected!!),
                            "✓ ${opt?.title ?: selected} selected"
                        )
                    }
                }
            },
            enabled = selected != null,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

// ── Multi choice ───────────────────────────────────────────────────────────

@Composable
private fun MultiChoiceBody(
    question: AgentQuestion.MultiChoice,
    onAnswered: (QuestionAnswer, String) -> Unit
) {
    val colors = LocalInterndraColors.current
    var selectedIds by remember(question.id) { mutableStateOf(setOf<String>()) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        question.options.forEach { option ->
            val isSelected = option.id in selectedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable {
                        selectedIds = if (isSelected) selectedIds - option.id else selectedIds + option.id
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isSelected) "☑" else "☐",
                    color = if (isSelected) Accent else TerminalWhite.copy(0.5f),
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.title, color = colors.inputTextColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (option.description.isNotBlank()) {
                        Text(option.description, color = TerminalWhite.copy(0.5f), fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Button(
            onClick = {
                val picked = question.options.filter { it.id in selectedIds }
                onAnswered(
                    QuestionAnswer.MultiChoiceAnswer(question.id, picked.map { it.id }),
                    "✓ ${picked.joinToString { it.title }} selected"
                )
            },
            enabled = selectedIds.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

// ── Confirmation ───────────────────────────────────────────────────────────

@Composable
private fun ConfirmationBody(
    question: AgentQuestion.Confirmation,
    onAnswered: (QuestionAnswer, String) -> Unit
) {
    val colors = LocalInterndraColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = TerminalYellow, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(question.question, color = TerminalYellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (question.commandPreview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                question.commandPreview,
                color = TerminalWhite.copy(0.7f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onAnswered(QuestionAnswer.ConfirmationAnswer(question.id, true), "✓ Confirmed") },
                colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                modifier = Modifier.weight(1f)
            ) { Text(question.confirmLabel, color = Color.Black, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { onAnswered(QuestionAnswer.ConfirmationAnswer(question.id, false), "Cancelled") },
                border = BorderStroke(1.dp, TerminalRed),
                modifier = Modifier.weight(1f)
            ) { Text(question.cancelLabel, color = TerminalRed, fontWeight = FontWeight.Bold) }
        }
    }
}

// ── Yes/No ─────────────────────────────────────────────────────────────────

@Composable
private fun YesNoBody(
    question: AgentQuestion.YesNo,
    onAnswered: (QuestionAnswer, String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onAnswered(QuestionAnswer.YesNoAnswer(question.id, true), "✓ Yes") },
            colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
            modifier = Modifier.weight(1f)
        ) { Text("Yes", color = Color.Black, fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = { onAnswered(QuestionAnswer.YesNoAnswer(question.id, false), "No") },
            border = BorderStroke(1.dp, SurfaceLight),
            modifier = Modifier.weight(1f)
        ) { Text("No", color = TerminalWhite, fontWeight = FontWeight.Bold) }
    }
}

// ── Text input ─────────────────────────────────────────────────────────────

@Composable
private fun TextInputBody(
    question: AgentQuestion.TextInput,
    onAnswered: (QuestionAnswer, String) -> Unit
) {
    val colors = LocalInterndraColors.current
    var value by remember(question.id) { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(question.placeholder, color = TerminalWhite.copy(0.4f), fontSize = 13.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) onAnswered(QuestionAnswer.TextAnswer(question.id, trimmed), "✓ $trimmed")
            },
            enabled = value.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

// ── Number input ───────────────────────────────────────────────────────────

@Composable
private fun NumberInputBody(
    question: AgentQuestion.NumberInput,
    onAnswered: (QuestionAnswer, String) -> Unit
) {
    val colors = LocalInterndraColors.current
    var value by remember(question.id) { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> if (v.all { it.isDigit() }) value = v.take(9) },
            placeholder = { Text(question.placeholder, color = TerminalWhite.copy(0.4f), fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                value.toIntOrNull()?.let { onAnswered(QuestionAnswer.NumberAnswer(question.id, it), "✓ $it") }
            },
            enabled = value.toIntOrNull() != null,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

// ── Option row ─────────────────────────────────────────────────────────────

@Composable
private fun OptionRow(
    option: QuestionOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalInterndraColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.14f)
                    option.recommended -> TerminalGreen.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "●" else "○",
                color = if (selected) Accent else TerminalWhite.copy(0.5f),
                fontSize = 16.sp
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.title, color = colors.inputTextColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (option.recommended) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, null, tint = TerminalYellow, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Recommended", color = TerminalYellow, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (option.description.isNotBlank()) {
                    Text(option.description, color = TerminalWhite.copy(0.55f), fontSize = 11.sp)
                }
                if (option.warning.isNotBlank()) {
                    Text("⚠ ${option.warning}", color = TerminalRed.copy(0.9f), fontSize = 11.sp)
                }
            }
        }
    }
}
