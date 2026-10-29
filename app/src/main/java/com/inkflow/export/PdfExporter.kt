package com.inkflow.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.inkflow.AppContainer
import com.inkflow.core.model.PageTemplate
import com.inkflow.ink.render.StrokePainter
import com.inkflow.ink.render.TemplateRenderer
import com.inkflow.pdf.PdfSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Exportação para PDF usando o PdfDocument nativo do Android.
 *  - Traços e modelos de papel saem VETORIAIS (nítidos em qualquer zoom/impressão).
 *  - Em cadernos de PDF, a página original entra como imagem de alta resolução (limitação:
 *    o texto do PDF original deixa de ser selecionável na versão anotada). O PDF original
 *    continua disponível, intacto, pela opção "Exportar original".
 */
class PdfExporter(private val c: AppContainer) {

    suspend fun exportAnnotated(notebookId: String, target: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nb = c.repository.getNotebook(notebookId) ?: error("Caderno não encontrado")
                val pages = c.repository.pagesOf(notebookId)
                val painter = StrokePainter()
                val templates = TemplateRenderer()
                val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                val pdfSrc = nb.pdfFile?.let { PdfSource(c.files.pdf(it)) }
                // Documentos longos usam resolução um pouco menor para limitar a memória.
                val rasterScale = if (pages.size > 80) 1.5f else 2f
                val doc = PdfDocument()
                try {
                    pages.forEachIndexed { i, p ->
                        val w = ceil(p.widthPt).toInt().coerceAtLeast(1)
                        val h = ceil(p.heightPt).toInt().coerceAtLeast(1)
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                        val canvas = page.canvas
                        if (pdfSrc != null && p.pdfPageIndex >= 0) {
                            val bmp = Bitmap.createBitmap(
                                (w * rasterScale).toInt().coerceAtLeast(1),
                                (h * rasterScale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            bmp.eraseColor(Color.WHITE)
                            pdfSrc.render(p.pdfPageIndex, bmp, Matrix().apply { setScale(rasterScale, rasterScale) }, forPrint = true)
                            canvas.drawBitmap(bmp, Matrix().apply { setScale(1f / rasterScale, 1f / rasterScale) }, bitmapPaint)
                            bmp.recycle()
                        } else {
                            templates.draw(canvas, PageTemplate.of(p.template), p.widthPt, p.heightPt)
                        }
                        painter.drawAll(canvas, c.repository.loadStrokes(p.id), null, emptySet())
                        doc.finishPage(page)
                        onProgress(i + 1, pages.size)
                    }
                    val out = c.app.contentResolver.openOutputStream(target, "wt") ?: error("Não foi possível criar o arquivo")
                    out.use { doc.writeTo(it) }
                } finally {
                    doc.close()
                    pdfSrc?.close()
                }
                pages.size
            }
        }

    /** Copia o PDF original, sem nenhuma alteração. */
    suspend fun exportOriginal(notebookId: String, target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val nb = c.repository.getNotebook(notebookId) ?: error("Caderno não encontrado")
            val name = nb.pdfFile ?: error("Este caderno não é um PDF importado")
            val out = c.app.contentResolver.openOutputStream(target, "wt") ?: error("Não foi possível criar o arquivo")
            out.use { o -> c.files.pdf(name).inputStream().use { it.copyTo(o) } }
            Unit
        }
    }
}
