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

    fun observeDocuments(folderId: Long?, query: String, sort: SortOrder): Flow<List<Document>> =
        db.documents().observeRows(folderId, query.trim(), sort.ordinal).map { rows -> rows.map(DocumentRow::toModel) }

    /** True once the library holds any document, regardless of filters. */
    fun observeHasDocuments(): Flow<Boolean> =
        db.documents().observeCount().map { it > 0 }.distinctUntilChanged()
}

class FolderRepository(private val db: OscannerDatabase) {

    fun observeFolders(): Flow<List<Folder>> =
        db.folders().observeAll().map { list -> list.map { Folder(it.id, it.name) } }

    suspend fun createFolder(name: String): Long =
        db.folders().insert(FolderEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
}

private fun DocumentRow.toModel() = Document(
    id = id,
    title = title,
    folderId = folderId,
    pageCount = pageCount,
    sizeBytes = sizeBytes,
    coverThumbPath = coverThumbPath,
    createdAt = createdAt,
    updatedAt = updatedAt,
    state = runCatching { ProcessingState.valueOf(state) }.getOrDefault(ProcessingState.Idle),
)
