package dev.olaf.oscanner.feature.library

import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.ProcessingState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySectionsTest {

    private fun doc(id: Long, folderId: Long?) =
        Document(id, "Doc $id", folderId, pageCount = 1, sizeBytes = 0, coverThumbPath = null, createdAt = 0, updatedAt = 0, state = ProcessingState.Idle)

    private val receipts = Folder(1, "Receipts")
    private val school = Folder(2, "School")

    @Test
    fun looseDocumentsComeFirstThenEachFolderInItsOrder() {
        // Newest first, as the library sorts them.
        val docs = listOf(doc(9, 2), doc(8, null), doc(7, 1), doc(6, 2), doc(5, null))
        val sections = librarySections(docs, listOf(receipts, school))
        assertEquals(listOf(null, receipts, school), sections.map { it.folder })
        assertEquals(listOf(listOf(8L, 5L), listOf(7L), listOf(9L, 6L)), sections.map { s -> s.documents.map { it.id } })
    }

    @Test
    fun emptyFoldersAndMissingOnesAddNoSection() {
        val sections = librarySections(listOf(doc(1, 2), doc(2, 42)), listOf(receipts, school))
        assertEquals(listOf(null, school), sections.map { it.folder })
        assertEquals(listOf(2L), sections[0].documents.map { it.id })
    }
}
