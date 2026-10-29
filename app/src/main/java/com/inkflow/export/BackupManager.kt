package com.inkflow.export

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.inkflow.AppContainer
import com.inkflow.AppFiles
import com.inkflow.data.db.InkDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup completo em um único .zip (formato versionado):
 *   manifest.json   → {"format":"inkflow-backup","version":1,"dbVersion":N,...}
 *   inkflow.db      → cópia consistente do banco
 *   pdfs/<arquivo>  → PDFs originais importados
 * Miniaturas não entram (são regeneradas).
 */
class BackupManager(private val c: AppContainer) {

    suspend fun export(target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = File(c.files.tmpDir, "backup-snapshot.db")
            snapshot.delete()
            snapshotDatabase(snapshot)
            val out = c.app.contentResolver.openOutputStream(target, "wt") ?: error("Não foi possível criar o arquivo")
            ZipOutputStream(out.buffered()).use { zip ->
                val manifest = JSONObject()
                    .put("format", FORMAT)
                    .put("version", VERSION)
                    .put("dbVersion", InkDatabase.VERSION)
                    .put("createdAt", System.currentTimeMillis())
                    .put("app", "InkFlow")
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(DB_ENTRY))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                c.files.pdfDir.listFiles()?.filter { it.isFile && it.name.endsWith(".pdf") }?.forEach { f ->
                    zip.putNextEntry(ZipEntry("pdfs/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            snapshot.delete()
            Unit
        }
    }

    /**
     * Restaura um backup. SUBSTITUI todos os cadernos atuais.
     * Tudo é extraído e validado numa pasta de preparação; a troca de arquivos acontece só no
     * próximo início do app, ANTES de o banco ser aberto ([applyPendingRestore]). Assim nunca se
     * substitui um banco que está em uso.
     */
    suspend fun restore(source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = pendingDir(c.files).apply { deleteRecursively(); mkdirs() }
            val stagingPdfs = File(staging, "pdfs").apply { mkdirs() }
            var manifestOk = false
            var dbFound = false
            try {
                val input = c.app.contentResolver.openInputStream(source) ?: error("Não foi possível abrir o backup")
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        when {
                            entry.isDirectory -> Unit
                            name == "manifest.json" -> {
                                val json = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                                require(json.optString("format") == FORMAT) { "Este arquivo não é um backup do InkFlow" }
                                require(json.optInt("version") <= VERSION) { "Backup criado por uma versão mais nova do app" }
                                require(json.optInt("dbVersion") <= InkDatabase.VERSION) { "Backup criado por uma versão mais nova do app" }
                                manifestOk = true
                            }
                            name == DB_ENTRY -> {
                                File(staging, DB_ENTRY).outputStream().use { zip.copyTo(it) }
                                dbFound = true
                            }
                            name.startsWith("pdfs/") -> {
                                // Proteção contra "zip slip": só nomes simples, sem diretórios.
                                val simple = name.removePrefix("pdfs/")
                                require(simple.isNotEmpty() && !simple.contains('/') && !simple.contains("..")) { "Backup inválido" }
                                File(stagingPdfs, simple).outputStream().use { zip.copyTo(it) }
                            }
                            else -> Unit
                        }
                        zip.closeEntry()
                    }
                }
                require(manifestOk && dbFound) { "Backup incompleto ou inválido" }
                File(staging, READY).writeText("ok")
            } catch (t: Throwable) {
                staging.deleteRecursively()
                throw t
            }
            Unit
        }
    }

    /** Reinicia o processo para reabrir o banco restaurado. */
    fun restartApp() {
        val ctx = c.app
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun snapshotDatabase(dest: File) {
        val sqlite = c.db.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= 30) {
            // VACUUM INTO gera uma cópia consistente mesmo com gravações acontecendo.
            sqlite.execSQL("VACUUM INTO '${dest.absolutePath.replace("'", "''")}'")
        } else {
            sqlite.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            c.files.databaseFile.copyTo(dest, overwrite = true)
        }
    }

    companion object {
        private const val READY = "READY"

        private fun pendingDir(files: AppFiles) = File(files.pdfDir.parentFile, "restore_pending")

        /** Chamado na inicialização, antes de qualquer acesso ao banco. */
        fun applyPendingRestore(files: AppFiles) {
            val staging = pendingDir(files)
            if (!File(staging, READY).exists()) {
                if (staging.exists()) staging.deleteRecursively()
                return
            }
            try {
                val dbFile = files.databaseFile
                dbFile.parentFile?.mkdirs()
                // Copia primeiro para um arquivo temporário; só então troca. Se algo falhar
                // aqui, o banco atual permanece intacto.
                val incoming = File(dbFile.path + ".restore")
                File(staging, DB_ENTRY).copyTo(incoming, overwrite = true)
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                File(dbFile.path + "-journal").delete()
                if (!incoming.renameTo(dbFile)) {
                    incoming.copyTo(dbFile, overwrite = true)
                    incoming.delete()
                }
                files.pdfDir.listFiles()?.forEach { it.delete() }
                File(staging, "pdfs").listFiles()?.forEach { it.copyTo(File(files.pdfDir, it.name), overwrite = true) }
                files.thumbDir.listFiles()?.forEach { it.delete() }
            } finally {
                staging.deleteRecursively()
            }
        }

        const val FORMAT = "inkflow-backup"
        const val VERSION = 1
        private const val DB_ENTRY = "inkflow.db"
    }
}
