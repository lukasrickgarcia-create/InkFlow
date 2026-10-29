package com.inkflow.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.inkflow.AppContainer
import com.inkflow.core.model.InkKind
import com.inkflow.core.model.NotebookKind
import com.inkflow.core.model.PageSize
import com.inkflow.core.model.PageTemplate
import com.inkflow.core.model.Stroke
import com.inkflow.core.model.StrokeChange
import com.inkflow.core.model.StrokeCodec
import com.inkflow.data.db.FolderEntity
import com.inkflow.data.db.NotebookEntity
import com.inkflow.data.db.NotebookWithCount
import com.inkflow.data.db.PageEntity
import com.inkflow.data.db.StrokeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Única porta de entrada para os dados persistidos. */
class NotebookRepository(private val c: AppContainer) {
    private val db get() = c.db

    // ------------------------------------------------------------ leitura
    fun observeNotebooks(): Flow<List<NotebookWithCount>> = db.notebooks().observeActive()
    fun observeTrash(): Flow<List<NotebookWithCount>> = db.notebooks().observeTrash()
    fun observeFolders(): Flow<List<FolderEntity>> = db.folders().observeAll()
    fun observeNotebook(id: String): Flow<NotebookEntity?> = db.notebooks().observe(id)
    fun observePages(notebookId: String): Flow<List<PageEntity>> = db.pages().observeFor(notebookId)

    suspend fun getNotebook(id: String): NotebookEntity? = withContext(Dispatchers.IO) { db.notebooks().get(id) }
    suspend fun getPage(id: String): PageEntity? = withContext(Dispatchers.IO) { db.pages().get(id) }
    suspend fun pagesOf(notebookId: String): List<PageEntity> = withContext(Dispatchers.IO) { db.pages().listFor(notebookId) }

    suspend fun loadStrokes(pageId: String): List<Stroke> = withContext(Dispatchers.IO) {
        db.strokes().forPage(pageId).mapNotNull { it.toStrokeOrNull() }
    }

    // ------------------------------------------------------------ cadernos
    suspend fun createNotebook(
        title: String,
        template: PageTemplate,
        subject: String,
        folderId: String?,
        coverColor: Int,
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            db.notebooks().insert(
                NotebookEntity(
                    id = id,
                    title = title.ifBlank { "Sem título" },
                    folderId = folderId,
                    subject = subject.trim(),
                    coverColor = coverColor,
                    kind = NotebookKind.NOTEBOOK,
                    defaultTemplate = template.name,
                    createdAt = now,
                    updatedAt = now,
                    openedAt = now,
                ),
            )
            db.pages().insert(newPage(id, 0, template, now))
        }
        id
    }

    /** Cria um caderno do tipo PDF com uma página por página do documento. */
    suspend fun createPdfNotebook(title: String, pdfFile: String, sizes: List<Pair<Float, Float>>, coverColor: Int): String =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            db.withTransaction {
                db.notebooks().insert(
                    NotebookEntity(
                        id = id,
                        title = title.ifBlank { "Documento" },
                        coverColor = coverColor,
                        kind = NotebookKind.PDF,
                        pdfFile = pdfFile,
                        defaultTemplate = PageTemplate.BLANK.name,
                        createdAt = now,
                        updatedAt = now,
                        openedAt = now,
                    ),
                )
                db.pages().insertAll(
                    sizes.mapIndexed { i, (w, h) ->
                        PageEntity(
                            id = UUID.randomUUID().toString(),
                            notebookId = id,
                            position = i,
                            template = PageTemplate.PDF.name,
                            widthPt = w,
                            heightPt = h,
                            pdfPageIndex = i,
                            updatedAt = now,
                        )
                    },
                )
            }
            id
        }

    suspend fun updateNotebook(id: String, transform: (NotebookEntity) -> NotebookEntity) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val n = db.notebooks().get(id) ?: return@withTransaction
            db.notebooks().update(transform(n))
        }
    }

    suspend fun markOpened(id: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        db.notebooks().markOpened(id, System.currentTimeMillis(), pageIndex)
    }

    suspend fun moveToTrash(id: String) = updateNotebook(id) { it.copy(deletedAt = System.currentTimeMillis()) }
    suspend fun restore(id: String) = updateNotebook(id) { it.copy(deletedAt = null) }

    /** Exclusão definitiva: remove o registro (páginas e traços em cascata), o PDF copiado e as miniaturas. */
    suspend fun deleteForever(id: String) = withContext(Dispatchers.IO) {
        val nb = db.notebooks().get(id) ?: return@withContext
        val pages = db.pages().listFor(id)
        db.notebooks().delete(id)
        nb.pdfFile?.let { c.files.pdf(it).delete() }
        pages.forEach { c.files.thumb(it.id).delete() }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        db.notebooks().trashed().forEach { deleteForever(it.id) }
    }

    // ------------------------------------------------------------ pastas
    suspend fun createFolder(name: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        db.folders().insert(FolderEntity(id, name.ifBlank { "Nova pasta" }, System.currentTimeMillis()))
        id
    }

    suspend fun renameFolder(id: String, name: String) = withContext(Dispatchers.IO) { db.folders().rename(id, name) }

    /** Excluir a pasta não exclui cadernos: eles voltam para "Sem pasta". */
    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.notebooks().clearFolder(id)
            db.folders().delete(id)
        }
    }

    // ------------------------------------------------------------ páginas
    /** Insere uma página na posição [position] e devolve essa posição. */
    suspend fun addPage(notebookId: String, position: Int, template: PageTemplate): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val count = db.pages().listFor(notebookId).size
            val pos = position.coerceIn(0, count)
            db.pages().shiftFrom(notebookId, pos)
            db.pages().insert(newPage(notebookId, pos, template, now))
            db.notebooks().touch(notebookId, now)
            pos
        }
    }

    suspend fun duplicatePage(pageId: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val src = db.pages().get(pageId) ?: return@withTransaction -1
            val pos = src.position + 1
            db.pages().shiftFrom(src.notebookId, pos)
            val copy = src.copy(id = UUID.randomUUID().toString(), position = pos, updatedAt = now)
            db.pages().insert(copy)
            val strokes = db.strokes().forPage(pageId).map {
                StrokeEntity(UUID.randomUUID().toString(), copy.id, it.kind, it.color, it.width, it.z, it.createdAt, it.points)
            }
            if (strokes.isNotEmpty()) db.strokes().upsertAll(strokes)
            db.notebooks().touch(src.notebookId, now)
            pos
        }
    }

    /** Exclui a página. Um caderno sempre mantém ao menos uma página. Devolve false se não excluiu. */
    suspend fun deletePage(pageId: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = db.withTransaction {
            val p = db.pages().get(pageId) ?: return@withTransaction false
            val pages = db.pages().listFor(p.notebookId)
            if (pages.size <= 1) return@withTransaction false
            db.pages().delete(pageId)
            val rest = pages.filter { it.id != pageId }.mapIndexed { i, pg -> pg.copy(position = i) }
            db.pages().updateAll(rest)
            db.notebooks().touch(p.notebookId, System.currentTimeMillis())
            true
        }
        if (deleted) c.files.thumb(pageId).delete()
        deleted
    }

    suspend fun movePage(notebookId: String, from: Int, to: Int) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val pages = db.pages().listFor(notebookId).toMutableList()
            if (from !in pages.indices || to !in pages.indices || from == to) return@withTransaction
            val p = pages.removeAt(from)
            pages.add(to, p)
            db.pages().updateAll(pages.mapIndexed { i, pg -> pg.copy(position = i) })
            db.notebooks().touch(notebookId, System.currentTimeMillis())
        }
    }

    /** Troca o modelo de papel. Traços não são tocados. */
    suspend fun setPageTemplate(pageId: String, template: PageTemplate) = withContext(Dispatchers.IO) {
        val p = db.pages().get(pageId) ?: return@withContext
        if (p.pdfPageIndex >= 0) return@withContext // páginas de PDF mantêm o documento como fundo
        db.pages().update(p.copy(template = template.name, updatedAt = System.currentTimeMillis()))
        c.thumbnails.schedule(pageId)
    }

    // ------------------------------------------------------------ traços
    /**
     * Persistência incremental: grava só o que mudou, numa transação, na thread de gravação.
     * Roda no escopo do app — sair do editor não cancela uma gravação em andamento.
     */
    fun persistChange(pageId: String, change: StrokeChange) {
        val removedIds = change.removed.map { it.id }
        val added = change.added
        c.appScope.launch(c.writeDispatcher) {
            try {
                val entities = added.map { it.toEntity(pageId) }
                val now = System.currentTimeMillis()
                db.withTransaction {
                    removedIds.chunked(500).forEach { db.strokes().deleteIds(it) }
                    if (entities.isNotEmpty()) db.strokes().upsertAll(entities)
                    val page = db.pages().get(pageId)
                    if (page != null) {
                        db.pages().touch(pageId, now)
                        db.notebooks().touch(page.notebookId, now)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao gravar traços da página $pageId", t)
            }
        }
    }

    private fun newPage(notebookId: String, position: Int, template: PageTemplate, now: Long) = PageEntity(
        id = UUID.randomUUID().toString(),
        notebookId = notebookId,
        position = position,
        template = template.name,
        widthPt = PageSize.A4_W,
        heightPt = PageSize.A4_H,
        updatedAt = now,
    )

    companion object {
        private const val TAG = "NotebookRepository"
    }
}

fun Stroke.toEntity(pageId: String) = StrokeEntity(
    id = id,
    pageId = pageId,
    kind = kind.name,
    color = color,
    width = width,
    z = z,
    createdAt = createdAt,
    points = StrokeCodec.encode(points),
)

fun StrokeEntity.toStrokeOrNull(): Stroke? = try {
    Stroke(id, InkKind.of(kind), color, width, StrokeCodec.decode(points), z, createdAt)
} catch (t: Throwable) {
    // Um traço corrompido não impede a abertura do restante da página.
    Log.w("NotebookRepository", "Traço ignorado ($id): ${t.message}")
    null
}
