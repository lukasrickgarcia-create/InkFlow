package com.inkflow.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Banco local (SQLite via Room) em modo WAL: cada traço é gravado numa transação curta.
 * Se o app fechar no meio da escrita, perde-se no máximo o traço que ainda estava sendo feito.
 *
 * Mudanças de esquema: aumentar [VERSION] e adicionar uma Migration — nunca usar
 * fallbackToDestructiveMigration, para não apagar cadernos.
 */
@Database(
    entities = [FolderEntity::class, NotebookEntity::class, PageEntity::class, StrokeEntity::class],
    version = InkDatabase.VERSION,
    exportSchema = true,
)
abstract class InkDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun notebooks(): NotebookDao
    abstract fun pages(): PageDao
    abstract fun strokes(): StrokeDao

    companion object {
        const val VERSION = 1
        const val NAME = "inkflow.db"

        fun build(context: Context): InkDatabase =
            Room.databaseBuilder(context.applicationContext, InkDatabase::class.java, NAME)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
