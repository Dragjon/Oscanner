package dev.olaf.oscanner.feature.library

import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Folder

/** Documents shown together under All: those in [folder], or in none when it's null. */
data class LibrarySection(val folder: Folder?, val documents: List<Document>)

/**
 * All documents grouped for the library: first those in no folder, then each folder's (A–Z, as
 * [folders] come), each group in the order [documents] are sorted. Empty groups are left out.
 * A document in a folder that's gone counts as in none.
 */
fun librarySections(documents: List<Document>, folders: List<Folder>): List<LibrarySection> {
    val known = folders.associateBy { it.id }
    val byFolder = documents.groupBy { doc -> doc.folderId?.takeIf { it in known } }
    return buildList {
        byFolder[null]?.let { add(LibrarySection(null, it)) }
        folders.forEach { folder -> byFolder[folder.id]?.let { add(LibrarySection(folder, it)) } }
    }
}
