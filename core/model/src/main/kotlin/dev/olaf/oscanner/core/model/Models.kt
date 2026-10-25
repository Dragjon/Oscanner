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
    /** The person's own labels, A–Z. A document can have several; folders hold one each. */
    val tags: List<String> = emptyList(),
    val note: String? = null,
)

/** A label in use on at least one document. */
data class Tag(
    val id: Long,
    val name: String,
)

/** What was scanned, which changes how pages are cut and how the PDF is laid out. */
enum class DocumentKind {
    Document,
    /** Front and back of a card, printed together at true size. */
    IdCard,
    /** Two-page spreads, each split at the spine. */
    Book,
    Whiteboard,
    /** A projected slide or a screen: colors kept, screen patterns reduced, a standard shape. */
    Slides,
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
    /** Marked as answered wrong: a test paper's question to practise again. */
    val wrong: Boolean = false,
)

/** A recognized word and its box, normalized to the page as shown (after crop and rotation). */
data class WordBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

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
/** The looks. [Photo] is for photographs, old prints especially: colors restored, dust taken out. */
enum class PageFilter { Original, Auto, Bright, Gray, Ink, Board, Photo }

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
    /** Typed text and stamps, drawn over the finished page after the strokes. */
    val texts: List<TextMark> = emptyList(),
    /** Clean-up: brush strokes painted over things to remove; filled from the paper around them. */
    val erase: List<InkMark> = emptyList(),
    /** Clean-up: brush strokes protecting areas from the automatic removals below. */
    val keep: List<InkMark> = emptyList(),
    /**
     * Clean-up: remove handwriting, keeping print. Colored pen anywhere, plus dark ink outside
     * the [printed] words.
     */
    val removePenInk: Boolean = false,
    /**
     * Where the print is, found when handwriting removal was turned on, normalized to the page
     * as shown. Ink inside it is print and stays. Null on pages from before this existed: those
     * remove colored pen only.
     */
    val printed: List<Area>? = null,
    /**
     * [printed] was found by how the marks look (each stretch of printed marks, exactly), not
     * from the words the reader was sure of (which can include neat handwriting).
     */
    val printByLook: Boolean = false,
    /** Clean-up: remove skin-colored shapes reaching in from the page edge, like holding fingers. */
    val removeFingers: Boolean = false,
    /** Clean-up: remove the back page's print showing through thin paper. */
    val removeShowThrough: Boolean = false,
    /** Clean-up: take the tint of coffee, tea and water stains out, keeping what's under them. */
    val removeStains: Boolean = false,
    /** Clean-up: bits of desk or background left in the page's corners and along its edges become paper. */
    val fillEdges: Boolean = false,
    /**
     * Areas blacked out, as outlines in the original photo (normalized upright-image
     * coordinates, like [quad]), so they stay over the same words when the page is re-cropped,
     * turned or flattened. Painted black before anything else reads the photo.
     */
    val redactions: List<List<Corner>> = emptyList(),
    /** Straighten text lines that bend with a curved page, like a book near its spine. */
    val flatten: Boolean = false,
    /**
     * The bend measured on this page when [flatten] was turned on, so every render, and every
     * box drawn on the page, uses the same map. Null until measured; reset when re-cropped.
     */
    val curve: PageCurve? = null,
    /** Take out the wavy bands and fine grids of a photographed screen or dot-printed picture. */
    val descreen: Boolean = false,
) {
    val adjusted: Boolean get() = brightness != 0f || contrast != 0f || detail != 0f

    val cleaned: Boolean get() = erase.isNotEmpty() || removePenInk || removeFingers || removeShowThrough || removeStains || fillEdges

    /** A quarter turn clockwise, with everything drawn on the page turned along. */
    fun rotatedClockwise(): PageEdits {
        fun List<InkMark>.turned() = map { mark -> mark.copy(points = mark.points.map { Corner(1f - it.y, it.x) }) }
        val turnedTexts = texts.map { it.copy(x = 1f - it.y, y = it.x, angle = (it.angle + 90) % 360) }
        val turnedPrinted = printed?.map { Area(1f - it.bottom, it.left, 1f - it.top, it.right) }
        return copy(
            rotation = (rotation + 90) % 360,
            marks = marks.turned(),
            texts = turnedTexts,
            erase = erase.turned(),
            keep = keep.turned(),
            printed = turnedPrinted,
        )
    }
}

/**
 * How text lines bend on a straightened page (before it's turned). Each line is
 * y = c0 + c1·x + c2·x² + c3·x³ in normalized page coordinates, measured between [CurveLine.from]
 * and [CurveLine.to] and held level beyond. Lines are top to bottom and never cross.
 *
 * Where the paper turns away from the camera, toward a book's spine, it's also squeezed from
 * side to side: [columns] are where, across the straightened page, evenly spaced columns of the
 * flat page come from (first 0, last 1, rising), and [widen] is how much wider the page is once
 * flat. Empty when the page isn't squeezed.
 */
data class PageCurve(val lines: List<CurveLine>, val columns: List<Float> = emptyList(), val widen: Float = 1f)

data class CurveLine(val coefficients: List<Float>, val from: Float, val to: Float)

/**
 * Text placed on a finished page. [x], [y] is its center, normalized to the page as shown;
 * [size] is its letter height as a share of the page's width, so it scales with the render.
 */
data class TextMark(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    /** ARGB. */
    val color: Int,
    /** Clockwise degrees, in quarter turns, so it stays upright with the page when turned. */
    val angle: Int = 0,
    /** A frame around it, like a rubber stamp. */
    val stamp: Boolean = false,
    val font: TextFont = TextFont.Sans,
    val bold: Boolean = false,
    /** ARGB filled behind the text, like a label; null for none. */
    val background: Int? = null,
    /**
     * A word retyped in place: the size of the word it replaces (as shares of the page's width,
     * in the mark's own frame), filled with [background] first so none of the old word shows
     * around a shorter new one. 0 for none.
     */
    val coverWidth: Float = 0f,
    val coverHeight: Float = 0f,
)

/** Typefaces every Android phone has. */
enum class TextFont { Sans, Serif, Mono, Hand }

/** How a pen stroke is drawn along its length. */
enum class LineStyle { Solid, Dashed, Dotted }

/** A rectangle normalized to the page as shown, edges 0…1. */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

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
    val style: LineStyle = LineStyle.Solid,
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
    /** Fixes the camera applies to every new scan. */
    val scanFixes: ScanFixes = ScanFixes(),
    val cameraOrientation: CameraOrientation = CameraOrientation.Auto,
    /** The welcome tour was seen (or skipped), so it doesn't show on opening again. */
    val welcomed: Boolean = false,
    /** Defaults were chosen (or left as they are) after the tour, so the setup doesn't show again. */
    val setUp: Boolean = false,
)

/** Which way up the camera takes pages: as the phone is held, or always one way. */
enum class CameraOrientation { Auto, Portrait, Landscape }

/**
 * Fixes applied to each new page as it's saved, set up in the camera so a scan comes out ready.
 * They're ordinary page edits, so any of them can be turned off on a page afterwards.
 */
data class ScanFixes(
    val removeHandwriting: Boolean = false,
    val removeFingers: Boolean = false,
    /** Straighten text lines that bend toward a book's spine. */
    val flatten: Boolean = true,
    /** Take out the bands and grids of a photographed screen or dot-printed picture. */
    val descreen: Boolean = false,
    /** The look every new page gets; null keeps each mode's own (Auto, Board, Original for cards). */
    val look: PageFilter? = null,
    /** The date and time the photo was taken, printed in a corner like a camera's date stamp. */
    val timestamp: Boolean = false,
    /** Background showing in a document's corners or along its edges is filled with paper. */
    val fillEdges: Boolean = false,
) {
    /** How many fixes are on, for the camera's button. */
    val count: Int get() = listOf(removeHandwriting, removeFingers, flatten, descreen, look != null, timestamp, fillEdges).count { it }
}
