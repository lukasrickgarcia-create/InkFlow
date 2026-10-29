package com.inkflow.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.inkflow.core.model.EraserMode
import com.inkflow.core.model.HighlighterColors
import com.inkflow.core.model.InkColors
import com.inkflow.core.model.Tool
import com.inkflow.core.model.alphaOf
import com.inkflow.core.model.withAlpha
import com.inkflow.data.settings.PenPreset
import com.inkflow.ink.view.ToolConfig
import com.inkflow.ui.common.ColorDot

@Composable
fun ToolOptionsPanel(
    tools: ToolConfig,
    presets: List<PenPreset>,
    onChange: (ToolConfig) -> Unit,
    onSavePreset: (PenPreset) -> Unit,
    onRemovePreset: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.width(380.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titleFor(tools.tool), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Fechar") }
            }
            when (tools.tool) {
                Tool.PEN, Tool.PENCIL -> {
                    val isPen = tools.tool == Tool.PEN
                    val color = if (isPen) tools.penColor else tools.pencilColor
                    val width = if (isPen) tools.penWidth else tools.pencilWidth
                    val alpha = alphaOf(color)
                    StrokePreview(color, width)
                    ColorGrid(InkColors, color) { c ->
                        val nc = withAlpha(c, alpha)
                        onChange(if (isPen) tools.copy(penColor = nc) else tools.copy(pencilColor = nc))
                    }
                    LabeledSlider("Espessura", "%.1f pt".format(width), width, 0.4f..8f) { w ->
                        onChange(if (isPen) tools.copy(penWidth = w) else tools.copy(pencilWidth = w))
                    }
                    LabeledSlider("Opacidade", "${(alpha * 100).toInt()}%", alpha, 0.2f..1f) { a ->
                        val nc = withAlpha(color, a)
                        onChange(if (isPen) tools.copy(penColor = nc) else tools.copy(pencilColor = nc))
                    }
                    Presets(
                        presets = presets,
                        onApply = { p ->
                            onChange(
                                when (p.tool) {
                                    Tool.HIGHLIGHTER -> tools.copy(tool = Tool.HIGHLIGHTER, highlighterColor = p.color, highlighterWidth = p.width)
                                    Tool.PENCIL -> tools.copy(tool = Tool.PENCIL, pencilColor = p.color, pencilWidth = p.width)
                                    else -> tools.copy(tool = Tool.PEN, penColor = p.color, penWidth = p.width)
                                },
                            )
                        },
                        onSave = { onSavePreset(PenPreset(tools.tool, color, width)) },
                        onRemove = onRemovePreset,
                    )
                    if (!isPen) {
                        Hint("O lápis varia a espessura com a velocidade do traço (canetas capacitivas não informam pressão).")
                    }
                }
                Tool.HIGHLIGHTER -> {
                    val color = tools.highlighterColor
                    val alpha = alphaOf(color)
                    StrokePreview(color, tools.highlighterWidth)
                    ColorGrid(HighlighterColors, color) { c -> onChange(tools.copy(highlighterColor = withAlpha(c, alpha))) }
                    LabeledSlider("Espessura", "%.0f pt".format(tools.highlighterWidth), tools.highlighterWidth, 4f..32f) {
                        onChange(tools.copy(highlighterWidth = it))
                    }
                    LabeledSlider("Transparência", "${(alpha * 100).toInt()}%", alpha, 0.15f..0.65f) {
                        onChange(tools.copy(highlighterColor = withAlpha(color, it)))
                    }
                    Hint("O marca-texto é sempre desenhado por baixo da tinta, para não apagar a escrita.")
                }
                Tool.ERASER -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = tools.eraserMode == EraserMode.STROKE,
                            onClick = { onChange(tools.copy(eraserMode = EraserMode.STROKE)) },
                            label = { Text("Traço inteiro") },
                        )
                        FilterChip(
                            selected = tools.eraserMode == EraserMode.AREA,
                            onClick = { onChange(tools.copy(eraserMode = EraserMode.AREA)) },
                            label = { Text("Por área") },
                        )
                    }
                    LabeledSlider("Tamanho", "${tools.eraserSizeDp.toInt()} dp", tools.eraserSizeDp, 8f..90f) {
                        onChange(tools.copy(eraserSizeDp = it))
                    }
                    Hint("Um círculo mostra a área da borracha enquanto você apaga. Desfazer recupera tudo.")
                }
                Tool.LASSO -> Hint("Circule os traços para selecioná-los. Arraste dentro da seleção para mover; use a barra para duplicar, recolorir ou excluir.")
                Tool.HAND -> Hint("Arraste com um dedo ou com a caneta para mover a página. Dois dedos fazem zoom.")
            }
        }
    }
}

private fun titleFor(t: Tool) = when (t) {
    Tool.PEN -> "Caneta"
    Tool.PENCIL -> "Lápis"
    Tool.HIGHLIGHTER -> "Marca-texto"
    Tool.ERASER -> "Borracha"
    Tool.LASSO -> "Seleção"
    Tool.HAND -> "Mover página"
}

@Composable
private fun StrokePreview(color: Int, widthPt: Float) {
    val c = Color(color)
    Canvas(Modifier.fillMaxWidth().height(36.dp).padding(vertical = 6.dp)) {
        val w = (widthPt * 1.4f).dp.toPx().coerceAtMost(size.height)
        val y = size.height / 2
        drawLine(c, Offset(8f, y + 4f), Offset(size.width * 0.33f, y - 4f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.33f, y - 4f), Offset(size.width * 0.66f, y + 4f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.66f, y + 4f), Offset(size.width - 8f, y - 4f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun ColorGrid(colors: List<Int>, current: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        colors.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c -> ColorDot(c, (c and 0xFFFFFF) == (current and 0xFFFFFF), 32.dp) { onPick(c) } }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = v.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun Presets(presets: List<PenPreset>, onApply: (PenPreset) -> Unit, onSave: () -> Unit, onRemove: (Int) -> Unit) {
    Text("Favoritas", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEachIndexed { i, p ->
            AssistChip(
                onClick = { onApply(p) },
                label = { Text("%.1f".format(p.width)) },
                leadingIcon = { ColorDot(p.color, false, 16.dp) { onApply(p) } },
                trailingIcon = {
                    IconButton(onClick = { onRemove(i) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remover", modifier = Modifier.size(14.dp))
                    }
                },
            )
        }
        AssistChip(onClick = onSave, label = { Text("Salvar atual") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
