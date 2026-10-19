package dev.olaf.oscanner.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    /**
     * [sort]: 0 newest first, 1 oldest first, 2 name A–Z.
     * [folderId] null means every folder.
     */
    @Query(
        """
        SELECT d.id, d.title, d.folderId, d.createdAt, d.updatedAt, d.state,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
            (SELECT COALESCE(SUM(p.sizeBytes), 0) FROM pages p WHERE p.documentId = d.id) AS sizeBytes,
            (SELECT p.thumbPath FROM pages p WHERE p.documentId = d.id ORDER BY p.position LIMIT 1) AS coverThumbPath
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

    @Query("SELECT COUNT(*) FROM documents WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("UPDATE documents SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("DELETE FROM documents")
    suspend fun deleteAll()
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
