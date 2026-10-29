package com.inkflow.ink.render

import android.graphics.Canvas
import android.graphics.Paint
import com.inkflow.core.model.PageTemplate

/**
 * Desenha o modelo de papel em coordenadas de página (pontos). Totalmente vetorial:
 * fica nítido em qualquer zoom e sai vetorial na exportação para PDF.
 * Cada thread deve ter sua própria instância.
 */
class TemplateRenderer {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFC9D3E0.toInt()
        strokeWidth = 0.6f
    }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFAFBCCD.toInt()
        strokeWidth = 0.9f
    }
    private val margin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE7A9A9.toInt()
        strokeWidth = 0.8f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFADB6C3.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        color = 0xFF8C97A6.toInt()
        textSize = 9f
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        color = 0xFF6F7B8C.toInt()
        textSize = 13f
        isFakeBoldText = true
    }

    fun draw(c: Canvas, t: PageTemplate, w: Float, h: Float) {
        when (t) {
            PageTemplate.BLANK, PageTemplate.PDF -> Unit
            PageTemplate.LINED -> lined(c, w, h, top = 72f, spacing = 24f, marginX = 60f)
            PageTemplate.GRID -> grid(c, w, h, MM5, line, everyStrong = 0)
            PageTemplate.DOTS -> dots(c, w, h, MM5)
            PageTemplate.CORNELL -> cornell(c, w, h)
            PageTemplate.WEEKLY -> weekly(c, w, h)
            PageTemplate.MONTHLY -> monthly(c, w, h)
            PageTemplate.TODO -> todo(c, w, h)
            PageTemplate.MATH -> {
                grid(c, w, h, MM5, line, everyStrong = 5)
                c.drawLine(48f, 0f, 48f, h, margin)
            }
            PageTemplate.DRAWING -> {
                c.drawRect(24f, 24f, w - 24f, h - 24f, line)
            }
        }
    }

    private fun lined(c: Canvas, w: Float, h: Float, top: Float, spacing: Float, marginX: Float) {
        var y = top
        while (y < h - 30f) {
            c.drawLine(0f, y, w, y, line)
            y += spacing
        }
        c.drawLine(marginX, 0f, marginX, h, margin)
    }

    private fun grid(c: Canvas, w: Float, h: Float, step: Float, p: Paint, everyStrong: Int) {
        var i = 0
        var x = 0f
        while (x <= w) {
            c.drawLine(x, 0f, x, h, if (everyStrong > 0 && i % everyStrong == 0) strong else p)
            x += step; i++
        }
        i = 0
        var y = 0f
        while (y <= h) {
            c.drawLine(0f, y, w, y, if (everyStrong > 0 && i % everyStrong == 0) strong else p)
            y += step; i++
        }
    }

    private fun dots(c: Canvas, w: Float, h: Float, step: Float) {
        var y = step
        while (y < h) {
            var x = step
            while (x < w) {
                c.drawCircle(x, y, 0.85f, dot)
                x += step
            }
            y += step
        }
    }

    private fun cornell(c: Canvas, w: Float, h: Float) {
        val header = 90f
        val summaryTop = h - 140f
        val cue = 170f
        c.drawText("Tema / Aula", 24f, 30f, text)
        c.drawText("Data", w - 120f, 30f, text)
        c.drawLine(24f, header, w - 24f, header, strong)
        c.drawLine(cue, header, cue, summaryTop, strong)
        c.drawText("Palavras-chave / Perguntas", 24f, header + 16f, text)
        c.drawText("Anotações", cue + 10f, header + 16f, text)
        var y = header + 40f
        while (y < summaryTop - 6f) {
            c.drawLine(cue + 8f, y, w - 24f, y, line)
            y += 24f
        }
        c.drawLine(24f, summaryTop, w - 24f, summaryTop, strong)
        c.drawText("Resumo", 24f, summaryTop + 16f, text)
    }

    private fun weekly(c: Canvas, w: Float, h: Float) {
        c.drawText("Semana de ____ / ____", 28f, 44f, title)
        val top = 64f
        val bottom = h - 28f
        val left = 28f
        val right = w - 28f
        val mid = (left + right) / 2f
        val rows = 4
        val rowH = (bottom - top) / rows
        val days = listOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado", "Domingo", "Notas")
        for (i in 0 until 8) {
            val col = i / rows
            val row = i % rows
            val x0 = if (col == 0) left else mid + 6f
            val x1 = if (col == 0) mid - 6f else right
            val y0 = top + row * rowH + 4f
            val y1 = y0 + rowH - 8f
            c.drawRect(x0, y0, x1, y1, line)
            c.drawText(days[i], x0 + 8f, y0 + 15f, text)
        }
    }

    private fun monthly(c: Canvas, w: Float, h: Float) {
        c.drawText("Mês: ______________", 28f, 44f, title)
        val left = 24f
        val right = w - 24f
        val top = 80f
        val bottom = h - 28f
        val cols = 7
        val rows = 6
        val cw = (right - left) / cols
        val headerH = 20f
        val names = listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
        for (i in 0 until cols) c.drawText(names[i], left + i * cw + 6f, top + 14f, text)
        val gridTop = top + headerH
        val rh = (bottom - gridTop) / rows
        for (r in 0..rows) c.drawLine(left, gridTop + r * rh, right, gridTop + r * rh, line)
        for (k in 0..cols) c.drawLine(left + k * cw, gridTop, left + k * cw, bottom, line)
    }

    private fun todo(c: Canvas, w: Float, h: Float) {
        c.drawText("Tarefas", 32f, 46f, title)
        var y = 80f
        while (y < h - 32f) {
            c.drawRect(32f, y - 11f, 44f, y + 1f, strong)
            c.drawLine(54f, y + 2f, w - 32f, y + 2f, line)
            y += 30f
        }
    }

    companion object {
        /** 5 mm em pontos tipográficos. */
        const val MM5 = 14.173f
    }
}
