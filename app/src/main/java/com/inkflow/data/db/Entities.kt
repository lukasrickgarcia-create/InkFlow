package com.inkflow.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "notebooks",
    indices = [Index("folderId"), Index("updatedAt"), Index("deletedAt")],
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String? = null,
    /** Matéria/disciplina (organização de estudos). Vazio = sem matéria. */
    val subject: String = "",
    val coverColor: Int,
    /** NOTEBOOK ou PDF. */
    val kind: String,
    /** Nome do arquivo do PDF original dentro de files/pdfs (só para kind = PDF). */
    val pdfFile: String? = null,
    val defaultTemplate: String,
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val openedAt: Long,
    /** Preenchido quando está na lixeira. */
    val deletedAt: Long? = null,
    val lastPageIndex: Int = 0,
    /** Versão do formato do documento, para migrações futuras. */
    val formatVersion: Int = 1,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("notebookId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val position: Int,
    val template: String,
    val widthPt: Float,
    val heightPt: Float,
    /** Página correspondente no PDF original; -1 para página sem PDF. */
    val pdfPageIndex: Int = -1,
    val title: String = "",
    val favorite: Boolean = false,
    val updatedAt: Long,
)

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** PEN, PENCIL ou HIGHLIGHTER. */
    val kind: String,
    val color: Int,
    val width: Float,
    val z: Long,
    val createdAt: Long,
    /** Pontos no formato binário versionado de StrokeCodec. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val points: ByteArray,
)

data class NotebookWithCount(
    @Embedded val notebook: NotebookEntity,
    val pageCount: Int,
)
