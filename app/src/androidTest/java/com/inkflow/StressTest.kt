package com.inkflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkflow.core.model.InkKind
import com.inkflow.core.model.PageTemplate
import com.inkflow.core.model.Stroke
import com.inkflow.core.model.StrokeChange
import com.inkflow.ink.edit.PageDocument
import com.inkflow.ink.render.StrokePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Testes no aparelho real: ./gradlew connectedAndroidTest
 * Os tempos medidos aparecem no Logcat com a tag "InkFlowStress".
 * Estes testes criam e depois excluem um caderno próprio.
 */
@RunWith(AndroidJUnit4::class)
class StressTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkFlowApp
    private val c = app.container

    private fun fakeStroke(i: Int, points: Int = 80): Stroke {
        val pts = FloatArray(points * 3)
        val baseY = 40f + (i % 60) * 12f
        val baseX = 30f + (i / 60) * 4f
        for (k in 0 until points) {
            pts[k * 3] = baseX + k * 6f
            pts[k * 3 + 1] = baseY + sin(k * 0.4f) * 4f
            pts[k * 3 + 2] = 1f
        }
        return Stroke(Stroke.newId(), InkKind.PEN, 0xFF111318.toInt(), 1.6f, pts, i.toLong(), System.currentTimeMillis())
    }

    @Test
    fun pageWith3000Strokes_persistsReloadsAndRenders() = runBlocking {
        val nbId = c.repository.createNotebook("Teste de carga", PageTemplate.LINED, "", null, 0xFF1E2A40.toInt())
        try {
            val page = c.repository.pagesOf(nbId).first()
            val strokes = (0 until 3000).map { fakeStroke(it) }

            val t0 = System.nanoTime()
            strokes.chunked(100).forEach { c.repository.persistChange(page.id, StrokeChange(emptyList(), it)) }
            // As gravações são assíncronas: espera a fila esvaziar.
            var tries = 0
            while (c.db.strokes().countFor(page.id) < 3000 && tries++ < 200) delay(50)
            val tSave = (System.nanoTime() - t0) / 1_000_000
            assertEquals(3000, c.db.strokes().countFor(page.id))

            val t1 = System.nanoTime()
            val loaded = c.repository.loadStrokes(page.id)
            val tLoad = (System.nanoTime() - t1) / 1_000_000
            assertEquals(3000, loaded.size)

            val bmp = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(3.2f, 3.2f)
            val t2 = System.nanoTime()
            StrokePainter().drawAll(canvas, loaded, null, emptySet())
            val tRender = (System.nanoTime() - t2) / 1_000_000
            bmp.recycle()

            Log.i("InkFlowStress", "3000 traços: gravar=${tSave}ms carregar=${tLoad}ms renderizar=${tRender}ms")
            assertTrue("Carregamento lento demais: $tLoad ms", tLoad < 5000)
        } finally {
            c.repository.deleteForever(nbId)
        }
    }

    @Test
    fun undoRedo_restoresExactState() {
        val sink = ArrayList<StrokeChange>()
        val doc = PageDocument("p", emptyList()) { _, ch -> sink.add(ch) }
        val a = fakeStroke(1)
        val b = fakeStroke(2)
        doc.apply(StrokeChange(emptyList(), listOf(a)))
        doc.apply(StrokeChange(emptyList(), listOf(b)))
        doc.apply(StrokeChange(listOf(a), emptyList()))
        assertEquals(listOf(b.id), doc.strokes.map { it.id })
        doc.undo()
        assertEquals(listOf(a.id, b.id), doc.strokes.map { it.id })
        doc.undo(); doc.undo()
        assertTrue(doc.strokes.isEmpty())
        doc.redo(); doc.redo(); doc.redo()
        assertEquals(listOf(b.id), doc.strokes.map { it.id })
        assertEquals(9, sink.size) // cada operação foi enviada para gravação
    }
}
