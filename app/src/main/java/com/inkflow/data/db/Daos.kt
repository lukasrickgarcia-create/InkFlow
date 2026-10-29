package com.inkflow.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert
    suspend fun insert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NotebookDao {
    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
           FROM notebooks n WHERE n.deletedAt IS NULL ORDER BY n.updatedAt DESC""",
    )
    fun observeActive(): Flow<List<NotebookWithCount>>

    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
           FROM notebooks n WHERE n.deletedAt IS NOT NULL ORDER BY n.deletedAt DESC""",
    )
    fun observeTrash(): Flow<List<NotebookWithCount>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observe(id: String): Flow<NotebookEntity?>

    @Query("SELECT * FROM notebooks WHERE deletedAt IS NOT NULL")
    suspend fun trashed(): List<NotebookEntity>

    @Insert
    suspend fun insert(n: NotebookEntity)

    @Update
    suspend fun update(n: NotebookEntity)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE notebooks SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: String, t: Long)

    @Query("UPDATE notebooks SET openedAt = :t, lastPageIndex = :page WHERE id = :id")
    suspend fun markOpened(id: String, t: Long, page: Int)

    @Query("UPDATE notebooks SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position")
    fun observeFor(notebookId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position")
    suspend fun listFor(notebookId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: String): PageEntity?

    @Insert
    suspend fun insert(p: PageEntity)

    @Insert
    suspend fun insertAll(p: List<PageEntity>)

    @Update
    suspend fun update(p: PageEntity)

    @Update
    suspend fun updateAll(p: List<PageEntity>)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pages SET position = position + 1 WHERE notebookId = :notebookId AND position >= :from")
    suspend fun shiftFrom(notebookId: String, from: Int)

    @Query("UPDATE pages SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: String, t: Long)
}

@Dao
interface StrokeDao {
    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY z, createdAt")
    suspend fun forPage(pageId: String): List<StrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<StrokeEntity>)

    @Query("DELETE FROM strokes WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM strokes WHERE pageId = :pageId")
    suspend fun countFor(pageId: String): Int
}
