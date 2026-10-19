package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.data.db.DocumentRow
import dev.olaf.oscanner.core.data.db.FolderEntity
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.ProcessingState
import dev.olaf.oscanner.core.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DocumentRepository(private val db: OscannerDatabase) {

    fun observeDocuments(folderId: Long?, query: String, sort: SortOrder): Flow<List<Document>> {
        val text = query.trim()
        val match = ftsQuery(text)
        val rows = if (match == null) {
            db.documents().observeRows(folderId, text, sort.ordinal)
        } else {
            db.documents().searchRows(folderId, text, match, sort.ordinal)
        }
        return rows.map { list -> list.map(DocumentRow::toModel) }
    }

    /** True once the library holds any document, regardless of filters. */
    fun observeHasDocuments(): Flow<Boolean> =
        db.documents().observeCount().map { it > 0 }.distinctUntilChanged()

    fun observeDocument(id: Long): Flow<Document?> =
        db.documents().observeRow(id).map { it?.toModel() }

    fun observeTrash(): Flow<List<Document>> =
        db.documents().observeTrash().map { rows -> rows.map(DocumentRow::toModel) }

    suspend fun rename(id: Long, title: String) {
        if (title.isBlank()) return
        db.documents().rename(id, title.trim(), System.currentTimeMillis())
    }

    suspend fun move(id: Long, folderId: Long?) = db.documents().move(id, folderId)

    /** Into Trash; kept 30 days, see [PageStore.purgeTrash]. */
    suspend fun moveToTrash(id: Long) = db.documents().setDeletedAt(id, System.currentTimeMillis())

    suspend fun restore(id: Long) = db.documents().setDeletedAt(id, null)
}

class FolderRepository(private val db: OscannerDatabase) {

    fun observeFolders(): Flow<List<Folder>> =
        db.folders().observeAll().map { list -> list.map { Folder(it.id, it.name) } }

    suspend fun createFolder(name: String): Long =
        db.folders().insert(FolderEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
}

/**
 * Turns what someone typed into an FTS4 query: every word must appear, each as a prefix, so
 * "elec bill" finds "Electricity bill". Quotes and operators are dropped. Null for no words.
 */
internal fun ftsQuery(text: String): String? {
    val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    return words.joinToString(" ") { "$it*" }
}

internal fun DocumentRow.toModel() = Document(
    id = id,
    title = title,
    folderId = folderId,
    pageCount = pageCount,
    sizeBytes = sizeBytes,
    coverThumbPath = coverThumbPath,
    createdAt = createdAt,
    updatedAt = updatedAt,
    state = runCatching { ProcessingState.valueOf(state) }.getOrDefault(ProcessingState.Idle),
    deletedAt = deletedAt,
)
