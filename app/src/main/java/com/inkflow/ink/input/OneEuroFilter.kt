package com.inkflow.ink.input

import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtro "1€" (Casiez et al., 2012): suaviza bastante em movimentos lentos (remove tremor)
 * e quase nada em movimentos rápidos (evita a sensação de traço atrasado).
 * Valores em pixels de tela; tempo em milissegundos.
 */
class OneEuroFilter(
    var minCutoff: Double = 1.5,
    var beta: Double = 0.02,
    private val dCutoff: Double = 1.0,
) {
    private var initialized = false
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = 0L

    fun reset() {
        initialized = false
    }

    fun filter(x: Double, timeMs: Long): Double {
        if (!initialized) {
            initialized = true
            xPrev = x
            dxPrev = 0.0
            tPrev = timeMs
            return x
        }
        var dt = (timeMs - tPrev) / 1000.0
        if (dt <= 0.0) dt = 1.0 / 240.0
        tPrev = timeMs
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    companion object {
        /** Converte o ajuste "Suavização" (0..1) em frequência de corte mínima. */
        fun minCutoffFor(smoothing: Float): Double {
            val s = smoothing.coerceIn(0f, 1f).toDouble()
            return 9.0 + (0.7 - 9.0) * s
        }
    }
}
