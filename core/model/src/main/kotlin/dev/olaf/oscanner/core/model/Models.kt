package dev.olaf.oscanner.core.model

/** A scanned document as the library shows it. Page count, size and cover come from its pages. */
data class Document(
    val id: Long,
    val title: String,
    val folderId: Long?,
    val pageCount: Int,
    val sizeBytes: Long,
    val coverThumbPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val state: ProcessingState,
)

/** Background work running on a document. Shown in place on its card, so the list never reflows. */
enum class ProcessingState { Idle, Enhancing, ReadingText, Failed }

data class Folder(
    val id: Long,
    val name: String,
)

enum class SortOrder { Newest, Oldest, Name }

enum class ThemeMode { Dark, Light, System }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = false,
    val sortOrder: SortOrder = SortOrder.Newest,
)
