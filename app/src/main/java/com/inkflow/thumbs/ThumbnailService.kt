package com.inkflow.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.inkflow.AppContainer
import com.inkflow.core.model.PageTemplate
import com.inkflow.ink.render.StrokePainter
import com.inkflow.ink.render.TemplateRenderer
import com.inkflow.pdf.PdfSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Miniaturas geradas em segundo plano, com espera (debounce) para não competir com a escrita.
 *
 * Decisão: uma corrotina no escopo do app em vez do WorkManager. A geração leva milissegundos,
 * não precisa sobreviver a reinícios (é refeita sob demanda) e assim evitamos uma dependência.
 */
class ThumbnailService(private val c: AppContainer) {
    private val jobs = HashMap<String, Job>()
    private val mutex = Mutex()
    private val painter = StrokePainter()
    private val templates = TemplateRenderer()

    private val _version = MutableStateFlow(0L)
    /** Incrementa sempre que alguma miniatura é regravada — a interface recarrega. */
    val version: StateFlow<Long> = _version

    fun schedule(pageId: String, delayMs: Long = 1500) {
        synchronized(jobs) {
            jobs.remove(pageId)?.cancel()
            jobs[pageId] = c.appScope.launch {
                delay(delayMs)
                render(pageId)
                synchronized(jobs) { jobs.remove(pageId) }
            }
        }
    }

    /** Carrega a miniatura, gerando-a se ainda não existir. */
    suspend fun load(pageId: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = c.files.thumb(pageId)
        if (!f.exists()) render(pageId)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private suspend fun render(pageId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val page = c.db.pages().get(pageId) ?: return@withContext
                val nb = c.db.notebooks().get(page.notebookId) ?: return@withContext
                val strokes = c.repository.loadStrokes(pageId)
                val s = WIDTH / page.widthPt
                val h = (page.heightPt * s).toInt().coerceIn(1, 4 * WIDTH)
                val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val pdfName = nb.pdfFile
                if (pdfName != null && page.pdfPageIndex >= 0) {
                    try {
                        PdfSource(c.files.pdf(pdfName)).use { src ->
                            src.render(page.pdfPageIndex, bmp, Matrix().apply { setScale(s, s) })
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Miniatura do PDF falhou: ${t.message}")
                    }
                }
                val canvas = Canvas(bmp)
                canvas.scale(s, s)
                templates.draw(canvas, PageTemplate.of(page.template), page.widthPt, page.heightPt)
                painter.drawAll(canvas, strokes, null, emptySet())
                writeAtomically(c.files.thumb(pageId), bmp)
                bmp.recycle()
                _version.value = _version.value + 1
                Unit
            } catch (t: Throwable) {
                Log.w(TAG, "Falha ao gerar miniatura de $pageId", t)
            }
        }
    }

    private fun writeAtomically(target: File, bmp: Bitmap) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        private const val TAG = "ThumbnailService"
        const val WIDTH = 360
    }
}
