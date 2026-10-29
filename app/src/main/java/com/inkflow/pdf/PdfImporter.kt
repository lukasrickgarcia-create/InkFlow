package com.inkflow.pdf

import android.net.Uri
import android.provider.OpenableColumns
import com.inkflow.AppContainer
import com.inkflow.core.model.CoverColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Importa um PDF escolhido no seletor do sistema (Storage Access Framework — sem permissões).
 * O arquivo é COPIADO para o armazenamento do app e nunca é modificado: as anotações ficam
 * separadas, no banco, por página.
 */
class PdfImporter(private val c: AppContainer) {

    sealed class Result {
        data class Ok(val notebookId: String, val pages: Int) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = c.app.contentResolver
        val displayName = queryName(uri) ?: "Documento.pdf"
        val id = UUID.randomUUID().toString()
        val fileName = "$id.pdf"
        val tmp = File(c.files.pdfDir, "$id.part")
        val dest = c.files.pdf(fileName)
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext Result.Error("Não foi possível abrir o arquivo.")
            input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) } }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return@withContext Result.Error("Falha ao salvar a cópia do PDF.")
            }
            val sizes = try {
                PdfSource(dest).use { src ->
                    (0 until src.pageCount).map { i ->
                        val s = src.pageSize(i)
                        s.width to s.height
                    }
                }
            } catch (e: SecurityException) {
                dest.delete()
                return@withContext Result.Error("Este PDF é protegido por senha e não pode ser aberto.")
            } catch (t: Throwable) {
                dest.delete()
                return@withContext Result.Error("Arquivo PDF inválido ou corrompido.")
            }
            if (sizes.isEmpty()) {
                dest.delete()
                return@withContext Result.Error("O PDF não tem páginas.")
            }
            val title = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
            val nbId = c.repository.createPdfNotebook(title, fileName, sizes, CoverColors[3])
            Result.Ok(nbId, sizes.size)
        } catch (t: Throwable) {
            tmp.delete()
            Result.Error("Falha na importação: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun queryName(uri: Uri): String? = try {
        c.app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }
}
