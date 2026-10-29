package com.inkflow.ink.geometry

import com.inkflow.core.util.FloatList
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Funções geométricas puras (sem dependência do Android) — testáveis na JVM.
 * Arrays de traço usam trios (x, y, w); polígonos usam pares (x, y).
 */
object Geometry {
    private const val STRIDE = 3

    fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0f) ((px - ax) * dx + (py - ay) * dy) / len2 else 0f
        t = t.coerceIn(0f, 1f)
        val qx = ax + t * dx - px
        val qy = ay + t * dy - py
        return qx * qx + qy * qy
    }

    /** O traço (com meia espessura [halfWidth]) encosta no círculo (cx, cy, r)? */
    fun strokeHitsCircle(points: FloatArray, halfWidth: Float, cx: Float, cy: Float, r: Float): Boolean {
        val n = points.size / STRIDE
        if (n == 0) return false
        val rr = r + halfWidth
        val rr2 = rr * rr
        if (n == 1) {
            val dx = points[0] - cx
            val dy = points[1] - cy
            return dx * dx + dy * dy <= rr2
        }
        for (i in 1 until n) {
            val d = distSqToSegment(
                cx, cy,
                points[(i - 1) * STRIDE], points[(i - 1) * STRIDE + 1],
                points[i * STRIDE], points[i * STRIDE + 1],
            )
            if (d <= rr2) return true
        }
        return false
    }

    /** Insere pontos interpolados para que nenhum segmento seja maior que [maxStep]. */
    fun densify(points: FloatArray, maxStep: Float): FloatArray {
        val n = points.size / STRIDE
        if (n < 2 || maxStep <= 0f) return points
        val out = FloatList(points.size + 32)
        out.add3(points[0], points[1], points[2])
        for (i in 1 until n) {
            val ax = points[(i - 1) * STRIDE]
            val ay = points[(i - 1) * STRIDE + 1]
            val aw = points[(i - 1) * STRIDE + 2]
            val bx = points[i * STRIDE]
            val by = points[i * STRIDE + 1]
            val bw = points[i * STRIDE + 2]
            val dx = bx - ax
            val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len > maxStep) {
                val k = ceil(len / maxStep).toInt()
                for (j in 1 until k) {
                    val t = j.toFloat() / k
                    out.add3(ax + dx * t, ay + dy * t, aw + (bw - aw) * t)
                }
            }
            out.add3(bx, by, bw)
        }
        return out.toArray()
    }

    /**
     * Borracha por área: remove os pontos dentro do círculo e devolve os pedaços restantes.
     * Retorna null se nada foi removido (o traço não muda).
     */
    fun splitOutsideCircle(points: FloatArray, cx: Float, cy: Float, r: Float, maxStep: Float): List<FloatArray>? {
        val dense = densify(points, maxStep)
        val n = dense.size / STRIDE
        val r2 = r * r
        val pieces = ArrayList<FloatArray>()
        val cur = FloatList()
        var removedAny = false
        for (i in 0 until n) {
            val x = dense[i * STRIDE]
            val y = dense[i * STRIDE + 1]
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r2) {
                removedAny = true
                if (cur.size > 0) {
                    pieces.add(cur.toArray())
                    cur.clear()
                }
            } else {
                cur.add3(x, y, dense[i * STRIDE + 2])
            }
        }
        if (!removedAny) return null
        if (cur.size > 0) pieces.add(cur.toArray())
        return pieces
    }

    /** Ponto dentro do polígono (regra par-ímpar). [poly] em pares (x, y). */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Fração dos pontos do traço que está dentro do laço (amostra no máximo ~64 pontos). */
    fun fractionInside(points: FloatArray, poly: FloatArray): Float {
        val n = points.size / STRIDE
        if (n == 0) return 0f
        val step = maxOf(1, n / 64)
        var total = 0
        var inside = 0
        var i = 0
        while (i < n) {
            total++
            if (pointInPolygon(points[i * STRIDE], points[i * STRIDE + 1], poly)) inside++
            i += step
        }
        return inside.toFloat() / total
    }
}
