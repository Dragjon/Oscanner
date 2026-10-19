package dev.olaf.oscanner.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val ROW_COLUMNS = """
    d.id, d.title, d.folderId, d.createdAt, d.updatedAt, d.state, d.deletedAt,
    (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
    (SELECT COALESCE(SUM(p.sizeBytes), 0) FROM pages p WHERE p.documentId = d.id) AS sizeBytes,
    (SELECT p.thumbPath FROM pages p WHERE p.documentId = d.id ORDER BY p.position LIMIT 1) AS coverThumbPath
"""

@Dao
interface DocumentDao {
    /**
     * [sort]: 0 newest first, 1 oldest first, 2 name A–Z.
     * [folderId] null means every folder.
     */
    @Query(
        """
        SELECT $ROW_COLUMNS
        FROM documents d
        WHERE d.deletedAt IS NULL
            AND (:folderId IS NULL OR d.folderId = :folderId)
            AND (:query = '' OR d.title LIKE '%' || :query || '%')
        ORDER BY
            CASE WHEN :sort = 0 THEN d.updatedAt END DESC,
            CASE WHEN :sort = 1 THEN d.updatedAt END ASC,
            CASE WHEN :sort = 2 THEN d.title END COLLATE NOCASE ASC
        """,
    )
    fun observeRows(folderId: Long?, query: String, sort: Int): Flow<List<DocumentRow>>

    /** Like [observeRows], but also matches words inside scans; [match] is an FTS4 query. */
    @Query(
        """
        SELECT $ROW_COLUMNS
        FROM documents d
        WHERE d.deletedAt IS NULL
            AND (:folderId IS NULL OR d.folderId = :folderId)
            AND (d.title LIKE '%' || :query || '%'
                OR d.id IN (SELECT p.documentId FROM pages p JOIN pages_fts f ON f.rowid = p.id WHERE pages_fts MATCH :match))
        ORDER BY
            CASE WHEN :sort = 0 THEN d.updatedAt END DESC,
            CASE WHEN :sort = 1 THEN d.updatedAt END ASC,
            CASE WHEN :sort = 2 THEN d.title END COLLATE NOCASE ASC
        """,
    )
    fun searchRows(folderId: Long?, query: String, match: String, sort: Int): Flow<List<DocumentRow>>

    @Query("SELECT $ROW_COLUMNS FROM documents d WHERE d.deletedAt IS NOT NULL ORDER BY d.deletedAt DESC")
    fun observeTrash(): Flow<List<DocumentRow>>

    @Query("SELECT $ROW_COLUMNS FROM documents d WHERE d.id = :id")
    fun observeRow(id: Long): Flow<DocumentRow?>

    @Query("SELECT $ROW_COLUMNS FROM documents d WHERE d.id = :id")
    suspend fun getRow(id: Long): DocumentRow?

    @Query("SELECT COUNT(*) FROM documents WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("UPDATE documents SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("UPDATE documents SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE documents SET folderId = :folderId WHERE id = :id")
    suspend fun move(id: Long, folderId: Long?)

    @Query("UPDATE documents SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE documents SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: Long, deletedAt: Long?)

    @Query("SELECT id FROM documents WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun trashedBefore(before: Long): List<Long>

    @Query("SELECT id FROM documents WHERE deletedAt IS NOT NULL")
    suspend fun trashedIds(): List<Long>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM documents")
    suspend fun deleteAll()
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    fun observe(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    suspend fun forDocument(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: Long): PageEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages WHERE documentId = :documentId")
    suspend fun lastPosition(documentId: Long): Int

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Query("UPDATE pages SET editsJson = :editsJson WHERE id = :id")
    suspend fun setEdits(id: Long, editsJson: String)

    @Query("UPDATE pages SET renderPath = :renderPath, thumbPath = :thumbPath, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun setRender(id: Long, renderPath: String, thumbPath: String, sizeBytes: Long)

    @Query("UPDATE pages SET ocrText = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String?)

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND ocrText IS NULL ORDER BY position")
    suspend fun unread(documentId: Long): List<PageEntity>

    @Query("UPDATE pages SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}
