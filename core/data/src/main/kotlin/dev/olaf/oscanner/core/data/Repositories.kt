package dev.olaf.oscanner.core.data

import androidx.room.withTransaction
import dev.olaf.oscanner.core.data.db.DocumentRow
import dev.olaf.oscanner.core.data.db.DocumentTagCrossRef
import dev.olaf.oscanner.core.data.db.TAG_SEPARATOR
import dev.olaf.oscanner.core.data.db.TagEntity
import dev.olaf.oscanner.core.data.db.FolderEntity
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.DocumentKind
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.ProcessingState
import dev.olaf.oscanner.core.model.SortOrder
import dev.olaf.oscanner.core.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DocumentRepository(private val db: OscannerDatabase) {

    fun observeDocuments(folderId: Long?, tagId: Long?, query: String, sort: SortOrder): Flow<List<Document>> {
        val text = query.trim()
        val match = ftsQuery(text)
        val rows = if (match == null) {
            db.documents().observeRows(folderId, tagId, text, sort.ordinal)
        } else {
            db.documents().searchRows(folderId, tagId, text, match, sort.ordinal)
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

    /** Blank clears the note. */
    suspend fun setNote(id: Long, note: String) = db.documents().setNote(id, note.trim().ifEmpty { null })

    /** Into Trash; kept 30 days, see [PageStore.purgeTrash]. */
    suspend fun moveToTrash(id: Long) = db.documents().setDeletedAt(id, System.currentTimeMillis())

    suspend fun restore(id: Long) = db.documents().setDeletedAt(id, null)
}

class FolderRepository(private val db: OscannerDatabase) {

    fun observeFolders(): Flow<List<Folder>> =
        db.folders().observeAll().map { list -> list.map { Folder(it.id, it.name) } }

    suspend fun createFolder(name: String): Long =
        db.folders().insert(FolderEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameFolder(id: Long, name: String) = db.folders().rename(id, name.trim())

    /** How many documents (outside Trash) are in [id]. */
    suspend fun documentCount(id: Long): Int = db.documents().idsInFolder(id).size

    /**
     * Deletes the folder [id]. Its documents stay, in no folder, unless [trashDocuments]: then
     * they go to Trash, where they can still be restored. Returns the documents trashed.
     */
    suspend fun deleteFolder(id: Long, trashDocuments: Boolean): List<Long> = db.withTransaction {
        val trashed = if (trashDocuments) db.documents().idsInFolder(id) else emptyList()
        val now = System.currentTimeMillis()
        trashed.forEach { db.documents().setDeletedAt(it, now) }
        db.documents().emptyFolder(id)
        db.folders().delete(id)
        trashed
    }
}

class TagRepository(private val db: OscannerDatabase) {

    fun observeTags(): Flow<List<Tag>> =
        db.tags().observeInUse().map { list -> list.map { Tag(it.id, it.name) } }

    /** Reuses an existing tag whatever its case, so "Tax" and "tax" stay one tag. */
    suspend fun addTag(documentId: Long, name: String) {
        val clean = cleanTagName(name) ?: return
        db.withTransaction {
            val id = db.tags().byName(clean)?.id ?: db.tags().insert(TagEntity(name = clean))
            db.tags().link(DocumentTagCrossRef(documentId, id))
        }
    }

    suspend fun removeTag(documentId: Long, name: String) {
        db.withTransaction {
            db.tags().unlink(documentId, name)
            db.tags().deleteUnused()
        }
    }
}

/** Single line, no control characters, at most 40 characters; null when nothing is left. */
internal fun cleanTagName(name: String): String? =
    name.replace(Regex("\\p{Cntrl}+"), " ").replace(Regex("\\s+"), " ").trim().take(40).trim().ifEmpty { null }

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
    kind = runCatching { DocumentKind.valueOf(kind) }.getOrDefault(DocumentKind.Document),
    tags = tagNames?.split(TAG_SEPARATOR).orEmpty().sortedWith(String.CASE_INSENSITIVE_ORDER),
    note = note,
)
