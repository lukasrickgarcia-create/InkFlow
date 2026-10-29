package com.inkflow.ink.render

import android.graphics.Canvas
import android.graphics.Paint
import com.inkflow.core.model.InkKind
import com.inkflow.core.model.Stroke

/**
 * Desenha traços num Canvas já transformado para coordenadas de página.
 * Cada thread deve ter sua própria instância (Paint não é thread-safe).
 */
class StrokePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, s: Stroke) {
        if (s.pointCount == 0) return
        if (!s.variableWidth) {
            paint.color = s.color
            paint.strokeWidth = s.width * s.maxWidthFactor
            canvas.drawPath(s.path, paint)
            return
        }
        val alpha = (s.color ushr 24) and 0xFF
        val translucent = alpha < 255
        // Segmentos com espessura variável se sobrepõem nas pontas; com tinta translúcida isso
        // criaria "contas" mais escuras. Desenhar numa camada opaca e aplicar o alfa no final evita.
        if (translucent) canvas.saveLayerAlpha(s.bounds, alpha)
        paint.color = s.color or 0xFF000000.toInt()
        val n = s.pointCount
        for (i in 1 until n) {
            paint.strokeWidth = s.width * (s.w(i - 1) + s.w(i)) * 0.5f
            canvas.drawLine(s.x(i - 1), s.y(i - 1), s.x(i), s.y(i), paint)
        }
        if (translucent) canvas.restore()
    }

    /** Desenha a lista respeitando camadas: marca-texto sempre por baixo da tinta. */
    fun drawAll(canvas: Canvas, strokes: List<Stroke>, visible: android.graphics.RectF?, hidden: Set<String>) {
        for (s in strokes) {
            if (s.kind != InkKind.HIGHLIGHTER) continue
            if (hidden.isNotEmpty() && s.id in hidden) continue
            if (visible != null && !android.graphics.RectF.intersects(s.bounds, visible)) continue
            draw(canvas, s)
        }
        for (s in strokes) {
            if (s.kind == InkKind.HIGHLIGHTER) continue
            if (hidden.isNotEmpty() && s.id in hidden) continue
            if (visible != null && !android.graphics.RectF.intersects(s.bounds, visible)) continue
            draw(canvas, s)
        }
    }
}
