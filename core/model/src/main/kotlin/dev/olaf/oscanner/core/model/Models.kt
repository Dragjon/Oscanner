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
    /** Set while the document sits in Trash. */
    val deletedAt: Long? = null,
)

/** Background work running on a document. Shown in place on its card, so the list never reflows. */
enum class ProcessingState { Idle, Enhancing, ReadingText, Failed }

data class Folder(
    val id: Long,
    val name: String,
)

/**
 * One page. [originalPath] is the untouched capture; [renderPath] is a cache rebuilt from
 * [originalPath] + [edits], and is null until the page has been rendered once.
 */
data class Page(
    val id: Long,
    val documentId: Long,
    val position: Int,
    val originalPath: String,
    val renderPath: String?,
    val thumbPath: String?,
    val edits: PageEdits,
)

/** A point in normalized image coordinates: (0,0) is the top-left of the upright image, (1,1) the bottom-right. */
data class Corner(val x: Float, val y: Float)

/** The page outline inside a photo, in normalized upright-image coordinates, clockwise from top-left. */
data class Quad(
    val topLeft: Corner,
    val topRight: Corner,
    val bottomRight: Corner,
    val bottomLeft: Corner,
) {
    val corners: List<Corner> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        /** The whole photo: what we fall back to when no page is found. */
        val Full = Quad(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f))

        fun of(corners: List<Corner>): Quad {
            require(corners.size == 4) { "A quad needs 4 corners, got ${corners.size}" }
            return Quad(corners[0], corners[1], corners[2], corners[3])
        }
    }
}

/** Enhancement looks. Names say what they do, see PLAN.md §4.3. */
enum class PageFilter { Original, Auto, Bright, Gray, Ink }

/** The recipe that turns an original photo into the page people see. Stored, never baked in. */
data class PageEdits(
    val quad: Quad = Quad.Full,
    val filter: PageFilter = PageFilter.Auto,
    /** Clockwise quarter turns applied after cropping: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** −1…1, applied after the look; 0 leaves it alone. */
    val brightness: Float = 0f,
    /** −1…1, applied after the look; 0 leaves it alone. */
    val contrast: Float = 0f,
    /** 0…1 extra sharpening on top of the look. */
    val detail: Float = 0f,
) {
    val adjusted: Boolean get() = brightness != 0f || contrast != 0f || detail != 0f
}

enum class SortOrder { Newest, Oldest, Name }

enum class ThemeMode { Dark, Light, System }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = false,
    val sortOrder: SortOrder = SortOrder.Newest,
)
