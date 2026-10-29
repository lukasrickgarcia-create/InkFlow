package com.inkflow

import com.inkflow.core.model.StrokeCodec
import com.inkflow.ink.geometry.Geometry
import com.inkflow.ink.input.OneEuroFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Testes de JVM (rodam sem tablet): ./gradlew test */
class CoreLogicTest {

    @Test
    fun codec_roundTrip_preservesEveryPoint() {
        val pts = FloatArray(3 * 500) { i -> (i * 0.731f) % 597f }
        val decoded = StrokeCodec.decode(StrokeCodec.encode(pts))
        assertArrayEquals(pts, decoded, 0f)
    }

    @Test
    fun codec_emptyStroke() {
        assertEquals(0, StrokeCodec.decode(StrokeCodec.encode(FloatArray(0))).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun codec_rejectsUnknownVersion() {
        val bytes = StrokeCodec.encode(floatArrayOf(1f, 2f, 1f))
        bytes[0] = 99
        StrokeCodec.decode(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun codec_rejectsTruncatedData() {
        val bytes = StrokeCodec.encode(floatArrayOf(1f, 2f, 1f, 3f, 4f, 1f))
        StrokeCodec.decode(bytes.copyOf(bytes.size - 4))
    }

    @Test
    fun hitTest_segmentNearCircle() {
        val line = floatArrayOf(0f, 0f, 1f, 100f, 0f, 1f)
        assertTrue(Geometry.strokeHitsCircle(line, 1f, 50f, 4f, 3.5f))
        assertFalse(Geometry.strokeHitsCircle(line, 1f, 50f, 10f, 3f))
    }

    @Test
    fun areaEraser_splitsLineInTwo() {
        val line = floatArrayOf(0f, 0f, 1f, 100f, 0f, 1f)
        val pieces = Geometry.splitOutsideCircle(line, 50f, 0f, 10f, 2f)
        assertNotNull(pieces)
        assertEquals(2, pieces!!.size)
        // Nenhum ponto restante dentro do círculo.
        for (p in pieces) {
            var i = 0
            while (i < p.size) {
                assertTrue(abs(p[i] - 50f) > 10f)
                i += 3
            }
        }
    }

    @Test
    fun areaEraser_untouchedStrokeReturnsNull() {
        val line = floatArrayOf(0f, 0f, 1f, 100f, 0f, 1f)
        assertNull(Geometry.splitOutsideCircle(line, 50f, 50f, 10f, 2f))
    }

    @Test
    fun densify_limitsSegmentLength() {
        val out = Geometry.densify(floatArrayOf(0f, 0f, 1f, 10f, 0f, 1f), 1f)
        assertEquals(11, out.size / 3)
    }

    @Test
    fun lasso_pointInPolygon() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(Geometry.pointInPolygon(5f, 5f, square))
        assertFalse(Geometry.pointInPolygon(15f, 5f, square))
        val stroke = floatArrayOf(2f, 2f, 1f, 8f, 8f, 1f, 20f, 20f, 1f)
        assertEquals(2f / 3f, Geometry.fractionInside(stroke, square), 0.001f)
    }

    @Test
    fun oneEuro_reducesJitterWhenStill() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.0)
        var t = 0L
        var maxDev = 0.0
        f.filter(100.0, t)
        repeat(100) { i ->
            t += 8
            val noisy = 100.0 + if (i % 2 == 0) 2.0 else -2.0
            maxDev = maxOf(maxDev, abs(f.filter(noisy, t) - 100.0))
        }
        assertTrue("desvio filtrado deveria ser bem menor que 2px: $maxDev", maxDev < 1.0)
    }

    @Test
    fun oneEuro_followsFastMovementClosely() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.02)
        var t = 0L
        var x = 0.0
        var out = 0.0
        repeat(60) {
            t += 8
            x += 16.0 // 2000 px/s
            out = f.filter(x, t)
        }
        assertTrue("atraso em movimento rápido deveria ser pequeno: ${x - out}", x - out < 40.0)
    }
}
