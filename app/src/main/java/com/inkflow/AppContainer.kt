package com.inkflow

import android.app.Application
import com.inkflow.data.db.InkDatabase
import com.inkflow.data.repo.NotebookRepository
import com.inkflow.data.settings.SettingsStore
import com.inkflow.export.BackupManager
import com.inkflow.export.PdfExporter
import com.inkflow.pdf.PdfImporter
import com.inkflow.thumbs.ThumbnailService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * Onde os arquivos ficam (armazenamento privado do app, sem permissões):
 *  - /data/data/com.inkflow/databases/inkflow.db  → cadernos, páginas e traços
 *  - /data/data/com.inkflow/files/pdfs/           → cópias dos PDFs importados (originais intactos)
 *  - /data/data/com.inkflow/files/thumbs/         → miniaturas (podem ser regeneradas)
 * Esses dados são apagados se o app for desinstalado — use o backup em Ajustes.
 */
class AppFiles(app: Application) {
    val pdfDir: File = File(app.filesDir, "pdfs").apply { mkdirs() }
    val thumbDir: File = File(app.filesDir, "thumbs").apply { mkdirs() }
    val tmpDir: File = File(app.cacheDir, "tmp").apply { mkdirs() }
    val databaseFile: File = app.getDatabasePath(InkDatabase.NAME)

    fun pdf(name: String): File = File(pdfDir, name)
    fun thumb(pageId: String): File = File(thumbDir, "$pageId.png")
}

/** Injeção de dependências manual — simples e sem bibliotecas extras. */
class AppContainer(val app: Application) {
    /** Escopo que sobrevive às telas: gravações nunca são canceladas ao sair do editor. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Uma única thread para gravações: preserva a ordem (traço → desfazer → refazer). */
    val writeDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "InkFlow-DB") }.asCoroutineDispatcher()

    val files = AppFiles(app).also { BackupManager.applyPendingRestore(it) }
    val settings = SettingsStore(app)

    @Volatile
    private var _db: InkDatabase? = null
    val db: InkDatabase
        get() = _db ?: synchronized(this) { _db ?: InkDatabase.build(app).also { _db = it } }

    fun closeDatabase() {
        synchronized(this) {
            _db?.close()
            _db = null
        }
    }

    val repository: NotebookRepository by lazy { NotebookRepository(this) }
    val thumbnails: ThumbnailService by lazy { ThumbnailService(this) }
    val pdfImporter: PdfImporter by lazy { PdfImporter(this) }
    val pdfExporter: PdfExporter by lazy { PdfExporter(this) }
    val backup: BackupManager by lazy { BackupManager(this) }
}
