package com.inkflow.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.SizeF
import java.io.Closeable
import java.io.File

/**
 * Acesso a um PDF com o PdfRenderer nativo do Android (motor PDFium do próprio sistema).
 * Sem biblioteca de terceiros — nenhuma restrição de licença.
 *
 * O PdfRenderer não é thread-safe e só permite uma página aberta por vez: todo acesso
 * passa por um lock. Só a região visível é renderizada, na resolução da tela, para
 * controlar memória em PDFs grandes.
 */
class PdfSource(file: File) : Closeable {
    val id: String = file.absolutePath
    private val lock = Any()
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (t: Throwable) {
        pfd.close()
        throw t
    }
    private var closed = false

    val pageCount: Int
        get() = synchronized(lock) { if (closed) 0 else renderer.pageCount }

    /** Tamanho da página em pontos (1/72 pol.). */
    fun pageSize(index: Int): SizeF = synchronized(lock) {
        check(!closed) { "PDF fechado" }
        val page = renderer.openPage(index)
        try {
            SizeF(page.width.toFloat(), page.height.toFloat())
        } finally {
            page.close()
        }
    }

    /**
     * Renderiza a página [index] no bitmap usando [transform] (página → pixels do bitmap).
     * Retorna false se o documento já foi fechado.
     */
    fun render(index: Int, target: Bitmap, transform: Matrix, forPrint: Boolean = false): Boolean = synchronized(lock) {
        if (closed || index < 0 || index >= renderer.pageCount) return false
        val page = renderer.openPage(index)
        try {
            val mode = if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            page.render(target, null, transform, mode)
        } finally {
            page.close()
        }
        true
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
        }
    }
}
