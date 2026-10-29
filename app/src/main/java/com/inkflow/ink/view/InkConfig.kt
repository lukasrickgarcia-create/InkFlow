package com.inkflow.ink.view

import com.inkflow.core.model.EraserMode
import com.inkflow.core.model.InkKind
import com.inkflow.core.model.Tool

/** Configuração da ferramenta ativa (espessuras em pontos de página). */
data class ToolConfig(
    val tool: Tool = Tool.PEN,
    val penColor: Int = 0xFF111318.toInt(),
    val penWidth: Float = 1.6f,
    val pencilColor: Int = 0xD9333333.toInt(),
    val pencilWidth: Float = 1.2f,
    val highlighterColor: Int = 0x66FFEB3B,
    val highlighterWidth: Float = 12f,
    val eraserMode: EraserMode = EraserMode.STROKE,
    /** Diâmetro da borracha em dp de tela (independe do zoom, como uma borracha real na mão). */
    val eraserSizeDp: Float = 24f,
) {
    fun inkKind(): InkKind? = when (tool) {
        Tool.PEN -> InkKind.PEN
        Tool.PENCIL -> InkKind.PENCIL
        Tool.HIGHLIGHTER -> InkKind.HIGHLIGHTER
        else -> null
    }

    fun colorFor(kind: InkKind): Int = when (kind) {
        InkKind.PEN -> penColor
        InkKind.PENCIL -> pencilColor
        InkKind.HIGHLIGHTER -> highlighterColor
    }

    fun widthFor(kind: InkKind): Float = when (kind) {
        InkKind.PEN -> penWidth
        InkKind.PENCIL -> pencilWidth
        InkKind.HIGHLIGHTER -> highlighterWidth
    }
}

/** Como interpretar toques de dedo. */
enum class TouchMode(val label: String, val description: String) {
    AUTO(
        "Automático",
        "Se o Android reconhecer uma caneta ativa (stylus), o dedo navega. Caso contrário, um toque escreve e dois dedos navegam.",
    ),
    FINGER_DRAWS(
        "Qualquer toque escreve",
        "Para canetas capacitivas (reconhecidas como dedo). Um toque escreve; dois dedos fazem zoom e rolagem.",
    ),
    FINGER_NAVIGATES(
        "Caneta escreve, dedo navega",
        "Só funciona se o Android identificar a caneta como stylus. Um dedo rola a página.",
    ),
    PEN_ONLY(
        "Somente caneta ativa",
        "Dedos nunca escrevem; dois dedos ainda fazem zoom. Exige caneta reconhecida como stylus.",
    );

    companion object {
        fun of(name: String?): TouchMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

enum class PressureMode(val label: String) {
    AUTO("Automático (só com stylus)"),
    ON("Sempre usar"),
    OFF("Nunca usar");

    companion object {
        fun of(name: String?): PressureMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Configuração de entrada (vem dos Ajustes). */
data class InputConfig(
    val touchMode: TouchMode = TouchMode.AUTO,
    val palmRejection: Boolean = true,
    /** Limiar de tamanho de contato (px de TOUCH_MAJOR). 0 = não calibrado: usa padrão por densidade. */
    val palmThresholdPx: Float = 0f,
    val smoothing: Float = 0.45f,
    val prediction: Boolean = true,
    val pressureMode: PressureMode = PressureMode.AUTO,
    val tapGestures: Boolean = true,
    /** Faixa na parte de baixo da tela onde toques são ignorados (para apoiar a mão). */
    val palmGuard: Boolean = true,
    /** Onde a faixa começa, como fração da altura da tela. */
    val palmGuardTop: Float = 0.62f,
    val stylusDetected: Boolean = false,
)
