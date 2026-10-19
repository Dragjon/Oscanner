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
    val kind: DocumentKind = DocumentKind.Document,
)

/** What was scanned, which changes how pages are cut and how the PDF is laid out. */
enum class DocumentKind {
    Document,
    /** Front and back of a card, printed together at true size. */
    IdCard,
    /** Two-page spreads, each split at the spine. */
    Book,
    Whiteboard,
}

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
    /** Recognized text, or null until the page has been read. */
    val text: String? = null,
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
enum class PageFilter { Original, Auto, Bright, Gray, Ink, Board }

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
    /**
     * Long side ÷ short side the page is straightened to, e.g. an ID card's 1.586; null keeps
     * the shape measured in the photo. Which side is long follows the photo.
     */
    val aspect: Float? = null,
    /** Pen, highlighter and signature strokes, drawn over the finished page. */
    val marks: List<InkMark> = emptyList(),
) {
    val adjusted: Boolean get() = brightness != 0f || contrast != 0f || detail != 0f

    /** A quarter turn clockwise, with marks turned along so they stay where they were drawn. */
    fun rotatedClockwise(): PageEdits = copy(
        rotation = (rotation + 90) % 360,
        marks = marks.map { mark -> mark.copy(points = mark.points.map { Corner(1f - it.y, it.x) }) },
    )
}

/**
 * One stroke on a finished page. Points are normalized to the page as shown (after crop and
 * rotation); width is a share of the page's width so it scales with the render.
 */
data class InkMark(
    val points: List<Corner>,
    /** ARGB. */
    val color: Int,
    val width: Float,
    /** Translucent and multiplied, so text under it stays readable. */
    val highlighter: Boolean = false,
)

enum class SortOrder { Newest, Oldest, Name }

/** PDF page size: Auto takes each scan's own shape. */
enum class PdfPageFormat { Auto, A4, Letter }

/** How much PDFs are compressed. */
enum class PdfQuality { Small, Balanced, Best }

enum class ThemeMode { Dark, Light, System }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = false,
    val sortOrder: SortOrder = SortOrder.Newest,
    /** Read the text in new scans in the background, for search and copy. */
    val autoReadText: Boolean = true,
    /** Tesseract language codes to read with, in order. */
    val textLanguages: List<String> = listOf("eng"),
    val pdfPageFormat: PdfPageFormat = PdfPageFormat.Auto,
    val pdfQuality: PdfQuality = PdfQuality.Balanced,
    /** The user's own text stamped across exported pages; empty for none. Oscanner adds none of its own. */
    val watermark: String = "",
    /** Ask for fingerprint, face or screen lock when opening the app. */
    val appLock: Boolean = false,
)
