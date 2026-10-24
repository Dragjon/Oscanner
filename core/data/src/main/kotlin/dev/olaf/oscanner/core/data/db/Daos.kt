package dev.olaf.oscanner.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val ROW_COLUMNS = """
    d.id, d.title, d.folderId, d.createdAt, d.updatedAt, d.state, d.deletedAt, d.kind,
    (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
    (SELECT COALESCE(SUM(p.sizeBytes), 0) FROM pages p WHERE p.documentId = d.id) AS sizeBytes,
    (SELECT p.thumbPath FROM pages p WHERE p.documentId = d.id ORDER BY p.position LIMIT 1) AS coverThumbPath,
    d.note,
    (SELECT GROUP_CONCAT(t.name, char(31)) FROM document_tags dt JOIN tags t ON t.id = dt.tagId WHERE dt.documentId = d.id) AS tagNames
"""

@Dao
interface DocumentDao {
    /**
     * [sort]: 0 newest first, 1 oldest first, 2 name A–Z.
     * [folderId] null means every folder; [tagId] null means any tags.
     */
    @Query(
        """
        SELECT $ROW_COLUMNS
        FROM documents d
        WHERE d.deletedAt IS NULL
            AND (:folderId IS NULL OR d.folderId = :folderId)
            AND (:tagId IS NULL OR d.id IN (SELECT documentId FROM document_tags WHERE tagId = :tagId))
            AND (:query = '' OR d.title LIKE '%' || :query || '%')
        ORDER BY
            CASE WHEN :sort = 0 THEN d.updatedAt END DESC,
            CASE WHEN :sort = 1 THEN d.updatedAt END ASC,
            CASE WHEN :sort = 2 THEN d.title END COLLATE NOCASE ASC
        """,
    )
    fun observeRows(folderId: Long?, tagId: Long?, query: String, sort: Int): Flow<List<DocumentRow>>

    /** Like [observeRows], but also matches notes, tag names and words inside scans; [match] is an FTS4 query. */
    @Query(
        """
        SELECT $ROW_COLUMNS
        FROM documents d
        WHERE d.deletedAt IS NULL
            AND (:folderId IS NULL OR d.folderId = :folderId)
            AND (:tagId IS NULL OR d.id IN (SELECT documentId FROM document_tags WHERE tagId = :tagId))
            AND (d.title LIKE '%' || :query || '%'
                OR d.note LIKE '%' || :query || '%'
                OR d.id IN (SELECT dt.documentId FROM document_tags dt JOIN tags t ON t.id = dt.tagId WHERE t.name LIKE '%' || :query || '%')
                OR d.id IN (SELECT p.documentId FROM pages p JOIN pages_fts f ON f.rowid = p.id WHERE pages_fts MATCH :match))
        ORDER BY
            CASE WHEN :sort = 0 THEN d.updatedAt END DESC,
            CASE WHEN :sort = 1 THEN d.updatedAt END ASC,
            CASE WHEN :sort = 2 THEN d.title END COLLATE NOCASE ASC
        """,
    )
    fun searchRows(folderId: Long?, tagId: Long?, query: String, match: String, sort: Int): Flow<List<DocumentRow>>

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

    @Query("SELECT id FROM documents WHERE state = :state")
    suspend fun idsInState(state: String): List<Long>

    @Query("UPDATE documents SET title = :title, updatedAt = :now, autoTitle = 0 WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    /** Names a document that still has the name it was made with; does nothing to one renamed since. */
    @Query("UPDATE documents SET title = :title, autoTitle = 0 WHERE id = :id AND autoTitle = 1")
    suspend fun autoName(id: Long, title: String)

    @Query("SELECT autoTitle FROM documents WHERE id = :id")
    suspend fun hasAutoTitle(id: Long): Boolean?

    @Query("UPDATE documents SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("UPDATE documents SET folderId = :folderId WHERE id = :id")
    suspend fun move(id: Long, folderId: Long?)

    @Query("SELECT id FROM documents WHERE folderId = :folderId AND deletedAt IS NULL")
    suspend fun idsInFolder(folderId: Long): List<Long>

    /** Every document in [folderId], Trash too, leaves it for no folder. */
    @Query("UPDATE documents SET folderId = NULL WHERE folderId = :folderId")
    suspend fun emptyFolder(folderId: Long)

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

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}

@Dao
interface TagDao {
    /** Tags on at least one document outside Trash, A–Z. */
    @Query(
        """
        SELECT * FROM tags WHERE id IN (
            SELECT dt.tagId FROM document_tags dt JOIN documents d ON d.id = dt.documentId WHERE d.deletedAt IS NULL
        ) ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeInUse(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): TagEntity?

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: DocumentTagCrossRef)

    @Query("DELETE FROM document_tags WHERE documentId = :documentId AND tagId = (SELECT id FROM tags WHERE name = :name COLLATE NOCASE)")
    suspend fun unlink(documentId: Long, name: String)

    /** Drops tags no document uses any more, so they stop being suggested. */
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM document_tags)")
    suspend fun deleteUnused()
}
