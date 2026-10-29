package com.inkflow.core.model

import android.graphics.Path
import android.graphics.RectF
import java.util.UUID

/**
 * Traço vetorial imutável.
 *
 * Os pontos ficam em coordenadas de página (pontos tipográficos), em trios (x, y, w).
 * O Path suavizado e os limites são calculados uma única vez, na criação, e reaproveitados
 * em toda renderização — isso evita reprocessar milhares de traços a cada quadro.
 * Por ser imutável, pode ser desenhado com segurança pela thread de renderização.
 */
class Stroke(
    val id: String,
    val kind: InkKind,
    val color: Int,
    /** Espessura nominal em pontos de página. */
    val width: Float,
    val points: FloatArray,
    /** Ordem de empilhamento. */
    val z: Long,
    val createdAt: Long,
) {
    val pointCount: Int get() = points.size / STRIDE
    val maxWidthFactor: Float
    val variableWidth: Boolean
    val bounds: RectF
    val path: Path

    /** Número de sequência atribuído pelo documento em memória (uso exclusivo da thread principal). */
    @Volatile
    var seq: Long = 0L

    init {
        val n = points.size / STRIDE
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var minW = Float.MAX_VALUE
        var maxW = 0f
        for (i in 0 until n) {
            val x = points[i * STRIDE]
            val y = points[i * STRIDE + 1]
            val w = points[i * STRIDE + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (w < minW) minW = w
            if (w > maxW) maxW = w
        }
        if (n == 0) {
            minX = 0f; minY = 0f; maxX = 0f; maxY = 0f; minW = 1f; maxW = 1f
        }
        maxWidthFactor = maxW
        variableWidth = n > 1 && (maxW - minW) > 0.02f
        val pad = width * maxW / 2f + 1f
        bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        path = buildSmoothPath(points)
    }

    fun x(i: Int): Float = points[i * STRIDE]
    fun y(i: Int): Float = points[i * STRIDE + 1]
    fun w(i: Int): Float = points[i * STRIDE + 2]

    /** Mesmo traço (mesmo id e ordem) deslocado — usado ao mover uma seleção. */
    fun translated(dx: Float, dy: Float): Stroke =
        Stroke(id, kind, color, width, shift(points, dx, dy), z, createdAt)

    fun recolored(newColor: Int): Stroke {
        // Preserva a transparência do traço (importante no marca-texto).
        val c = (color and 0xFF000000.toInt()) or (newColor and 0x00FFFFFF)
        return Stroke(id, kind, c, width, points, z, createdAt)
    }

    /** Cópia com novo id (duplicar seleção). */
    fun duplicate(dx: Float, dy: Float, newZ: Long): Stroke =
        Stroke(newId(), kind, color, width, shift(points, dx, dy), newZ, System.currentTimeMillis())

    /** Novo traço com os pontos dados, herdando estilo e ordem (pedaços da borracha por área). */
    fun withPoints(newPoints: FloatArray): Stroke =
        Stroke(newId(), kind, color, width, newPoints, z, createdAt)

    companion object {
        const val STRIDE = 3

        fun newId(): String = UUID.randomUUID().toString()

        private fun shift(src: FloatArray, dx: Float, dy: Float): FloatArray {
            val p = src.copyOf()
            var i = 0
            while (i < p.size) {
                p[i] += dx
                p[i + 1] += dy
                i += STRIDE
            }
            return p
        }

        /**
         * Curva suave por pontos médios: cada ponto amostrado vira ponto de controle de uma
         * Bézier quadrática que liga os pontos médios vizinhos. O mesmo algoritmo é usado no traço
         * ao vivo, então não há "salto" visual quando o traço é finalizado.
         */
        fun buildSmoothPath(points: FloatArray): Path {
            val path = Path()
            val n = points.size / STRIDE
            if (n == 0) return path
            val x0 = points[0]
            val y0 = points[1]
            path.moveTo(x0, y0)
            if (n == 1) {
                // Um toque vira um ponto: linha mínima com ponta arredondada.
                path.lineTo(x0 + 0.01f, y0)
                return path
            }
            for (i in 1 until n) {
                val px = points[(i - 1) * STRIDE]
                val py = points[(i - 1) * STRIDE + 1]
                val cx = points[i * STRIDE]
                val cy = points[i * STRIDE + 1]
                path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
            }
            path.lineTo(points[(n - 1) * STRIDE], points[(n - 1) * STRIDE + 1])
            return path
        }
    }
}

/** Alteração atômica no conjunto de traços de uma página. Todo comando de edição vira uma destas. */
class StrokeChange(val removed: List<Stroke>, val added: List<Stroke>) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
    fun inverse(): StrokeChange = StrokeChange(added, removed)
}
