package com.inkflow.core.model

/** Ferramenta ativa no editor. */
enum class Tool { PEN, PENCIL, HIGHLIGHTER, ERASER, LASSO, HAND }

/** Tipo de tinta gravado em cada traço. */
enum class InkKind {
    PEN, PENCIL, HIGHLIGHTER;

    companion object {
        fun of(name: String?): InkKind = entries.firstOrNull { it.name == name } ?: PEN
    }
}

enum class EraserMode { STROKE, AREA }

/** Modelos de papel. Desenhados de forma vetorial; trocar o modelo nunca altera os traços. */
enum class PageTemplate(val label: String) {
    BLANK("Branca"),
    LINED("Pautada"),
    GRID("Quadriculada"),
    DOTS("Pontilhada"),
    CORNELL("Estudo (Cornell)"),
    WEEKLY("Planejamento semanal"),
    MONTHLY("Planejamento mensal"),
    TODO("Lista de tarefas"),
    MATH("Matemática"),
    DRAWING("Desenho"),
    PDF("Página de PDF");

    companion object {
        fun of(name: String?): PageTemplate = entries.firstOrNull { it.name == name } ?: BLANK
        val selectable: List<PageTemplate> get() = entries.filter { it != PDF }
    }
}

/**
 * Coordenadas de página são em pontos tipográficos (1/72 pol.), independentes da tela.
 * Assim o traço mantém a proporção em qualquer zoom e a exportação para PDF é direta.
 */
object PageSize {
    const val A4_W = 595f
    const val A4_H = 842f
}

object NotebookKind {
    const val NOTEBOOK = "NOTEBOOK"
    const val PDF = "PDF"
}

/** Cores de capa disponíveis. */
val CoverColors: List<Int> = listOf(
    0xFF1E2A40.toInt(), 0xFF3B5B8C.toInt(), 0xFF2F6F5E.toInt(), 0xFF7A3E48.toInt(),
    0xFFB4643C.toInt(), 0xFFC9A227.toInt(), 0xFF5B4B8A.toInt(), 0xFF4A4F57.toInt(),
)

/** Paleta de tintas. */
val InkColors: List<Int> = listOf(
    0xFF111318.toInt(), 0xFF1F4E9C.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFF6D4C41.toInt(),
    0xFF757575.toInt(), 0xFFAD1457.toInt(), 0xFF0D47A1.toInt(), 0xFFFFFFFF.toInt(),
)

/** Paleta do marca-texto (sem alfa; a transparência é aplicada separadamente). */
val HighlighterColors: List<Int> = listOf(
    0xFFFFEB3B.toInt(), 0xFF76FF03.toInt(), 0xFF40C4FF.toInt(), 0xFFFF80AB.toInt(),
    0xFFFFAB40.toInt(), 0xFFB388FF.toInt(),
)

fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (a shl 24) or (color and 0x00FFFFFF)
}

fun alphaOf(color: Int): Float = ((color ushr 24) and 0xFF) / 255f
