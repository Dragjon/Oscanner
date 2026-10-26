package dev.olaf.oscanner.core.data

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dev.olaf.oscanner.core.data.db.DocumentEntity
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.data.db.PageEntity
import dev.olaf.oscanner.core.imaging.BookPages
import dev.olaf.oscanner.core.imaging.BookSplitter
import dev.olaf.oscanner.core.imaging.DocumentDetector
import dev.olaf.oscanner.core.imaging.Figures
import dev.olaf.oscanner.core.imaging.IdPhoto
import dev.olaf.oscanner.core.imaging.PageSignature
import dev.olaf.oscanner.core.imaging.CornerFinder
import dev.olaf.oscanner.core.imaging.CornerNet
import dev.olaf.oscanner.core.imaging.PageFinder
import dev.olaf.oscanner.core.imaging.PageNet
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.imaging.SlideShape
import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.DocumentKind
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.PageCurve
import dev.olaf.oscanner.core.model.PRINT_FINDER
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.ProcessingState
import dev.olaf.oscanner.core.model.ScanFixes
import dev.olaf.oscanner.core.model.TextMark
import dev.olaf.oscanner.core.model.IdBackground
import dev.olaf.oscanner.core.model.IdPhotoSize
import dev.olaf.oscanner.core.model.Quad
import dev.olaf.oscanner.core.model.WordBox
import dev.olaf.oscanner.core.ocr.OcrResult
import dev.olaf.oscanner.core.ocr.PaddleReader
import dev.olaf.oscanner.core.pdf.DocxWriter
import dev.olaf.oscanner.core.pdf.LayoutBlock
import dev.olaf.oscanner.core.pdf.LayoutParagraph
import dev.olaf.oscanner.core.pdf.OfficeReader
import dev.olaf.oscanner.core.pdf.LayoutPicture
import dev.olaf.oscanner.core.pdf.PageLayout
import dev.olaf.oscanner.core.pdf.ParagraphStyle
import dev.olaf.oscanner.core.pdf.PptxSlide
import dev.olaf.oscanner.core.pdf.PptxWriter
import dev.olaf.oscanner.core.pdf.JpegPdfWriter
import dev.olaf.oscanner.core.pdf.PdfPage
import dev.olaf.oscanner.core.pdf.PdfPageSize
import dev.olaf.oscanner.core.pdf.PdfWord
import dev.olaf.oscanner.core.pdf.TableLayout
import dev.olaf.oscanner.core.pdf.XlsxWriter
import dev.olaf.oscanner.core.model.PdfPageFormat
import dev.olaf.oscanner.core.model.PdfQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A photo waiting to become a page, with the outline the camera saw (if any). */
data class CapturedImage(
    val file: File,
    /** Where the camera's live outline was at the moment of capture. */
    val liveQuad: Quad?,
    /** The page's outline, found in the photo or set by hand; null to find it when saved. */
    val quad: Quad? = null,
    /**
     * Already a flat, clean page (a page of an imported PDF): kept whole and as it is, with no
     * outline to find, no look applied and none of the camera's fixes.
     */
    val flat: Boolean = false,
    /** What's on the page when that's already known, as an imported PDF's own text is. */
    val words: OcrResult? = null,
)

/** A capture moved into its document and ready to be a page. */
private data class Prepared(val original: File, val edits: PageEdits, val flat: Boolean, val words: OcrResult? = null)

/**
 * Pages on disk and in the database. Layout: `files/docs/<docId>/<uuid>/original.jpg`, plus
 * `render-<time>.jpg` and `thumb-<time>.webp` caches that are rebuilt whenever the edits change.
 */
class PageStore(
    private val context: Context,
    private val db: OscannerDatabase,
    private val text: TextReader,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Full renders hold several large buffers; two at once keeps memory sane on small phones. */
    private val renderPermits = Semaphore(2)
    private val renderJobs = ConcurrentHashMap<Long, Job>()
    /** Clean-up still on its way to a page (handwriting found later, or copied from another page). */
    private val cleanupJobs = ConcurrentHashMap<Long, Job>()
    private val docsDir get() = File(context.filesDir, "docs")
    private val exportsDir get() = File(context.cacheDir, "exports")

    fun observePages(documentId: Long): Flow<List<Page>> =
        db.pages().observe(documentId).map { list -> list.map(PageEntity::toModel) }

    suspend fun pages(documentId: Long): List<Page> = db.pages().forDocument(documentId).map(PageEntity::toModel)

    suspend fun page(id: Long): Page? = db.pages().get(id)?.toModel()

    /** Words read on a page, with boxes on the page as shown; empty until it has been read. */
    suspend fun words(pageId: Long): List<WordBox> {
        val page = db.pages().get(pageId) ?: return emptyList()
        return withContext(Dispatchers.IO) { visibleWords(page) }.map { WordBox(it.text, it.left, it.top, it.right, it.bottom) }
    }

    /**
     * Words for a page's text layer, minus any under a blacked-out area. A page is read again
     * whenever its areas change, so this only matters while that's still queued.
     */
    private fun visibleWords(page: PageEntity): List<PdfWord> {
        val words = text.words(page)
        val edits = EditsJson.read(page.editsJson)
        if (edits.redactions.isEmpty()) return words
        val shapes = QuadGeometry.redactionsOnPage(edits)
        return words.filterNot { w ->
            val center = Corner((w.left + w.right) / 2, (w.top + w.bottom) / 2)
            shapes.any { QuadGeometry.contains(it, center) }
        }
    }

    suspend fun kindOf(documentId: Long): DocumentKind = db.documents().getRow(documentId)?.kind
        ?.let { name -> DocumentKind.entries.firstOrNull { it.name == name } } ?: DocumentKind.Document

    /** Makes a new document from captures and starts rendering it. Returns its id. */
    suspend fun createDocument(
        title: String,
        folderId: Long?,
        images: List<CapturedImage>,
        kind: DocumentKind = DocumentKind.Document,
    ): Long {
        require(images.isNotEmpty())
        val now = System.currentTimeMillis()
        val docId = db.documents().insert(
            DocumentEntity(
                title = title,
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                state = ProcessingState.Enhancing.name,
                kind = kind.name,
                // An ID card's text is a name and numbers, not a title.
                autoTitle = kind != DocumentKind.IdCard,
            ),
        )
        addPages(docId, images)
        return docId
    }

    /** Appends captures to the end of [documentId] and renders them. */
    suspend fun addPages(documentId: Long, images: List<CapturedImage>) {
        val kind = kindOf(documentId)
        val fixes = settings.settings.first().scanFixes
        val prepared = withContext(Dispatchers.IO) {
            images.flatMap { image ->
                if (image.flat) {
                    listOf(Prepared(adopt(documentId, image), PageEdits(quad = Quad.Full, filter = PageFilter.Original), flat = true, image.words))
                } else {
                    prepare(documentId, image, kind).map { (original, edits) -> Prepared(original, withFixes(original, edits, fixes, kind), flat = false) }
                }
            }
        }
        val added = mutableListOf<Long>()
        val known = mutableListOf<Pair<PageEntity, OcrResult>>()
        db.withTransaction {
            var position = db.pages().lastPosition(documentId)
            prepared.forEach { (original, edits, flat, words) ->
                val entity = PageEntity(
                    documentId = documentId,
                    position = ++position,
                    originalPath = original.path,
                    width = 0,
                    height = 0,
                    sizeBytes = original.length(),
                    editsJson = EditsJson.write(edits),
                )
                val id = db.pages().insert(entity)
                if (words != null) known += entity.copy(id = id) to words
                // Pages of a PDF stay as they were, and slides have no handwriting: clean-up is
                // left to the person.
                if (!flat && kind != DocumentKind.Slides) added += id
            }
            db.documents().touch(documentId, System.currentTimeMillis())
        }
        // The pages are saved: finish them even if whoever asked has gone (the screen closed),
        // or they'd sit half-made.
        withContext(NonCancellable) {
            // Text that came with a page needn't be read: it's kept now, and reading skips the page.
            known.forEach { (page, words) -> text.keep(page, words) }
            if (known.isNotEmpty()) text.nameAfterHeading(documentId)
            renderDocument(documentId)
            text.scheduleIfWanted(documentId, delay = Duration.ofSeconds(30))
            if (fixes.removeHandwriting) removeHandwritingLater(added)
        }
    }

    /** The camera's fixes on a new page's edits. Handwriting comes after: it needs the page read. */
    private fun withFixes(original: File, edits: PageEdits, fixes: ScanFixes, kind: DocumentKind): PageEdits {
        var fixed = edits.copy(
            removeFingers = edits.removeFingers || fixes.removeFingers,
            // Paper pages only: a card's or a slide's own edges are part of it.
            fillEdges = edits.fillEdges || (fixes.fillEdges && kind in PAPER_KINDS),
            descreen = edits.descreen || fixes.descreen,
            filter = fixes.look ?: edits.filter,
        )
        if (fixes.flatten && !fixed.flatten) {
            fixed = fixed.copy(flatten = true).let { it.copy(curve = measureCurve(original.path, it)) }
        }
        if (fixes.timestamp) fixed = fixed.copy(texts = fixed.texts + timeStamp(original))
        return fixed
    }

    /**
     * When the photo was taken (from its camera data, else the file's time), as a small orange
     * stamp in the bottom-right corner. It's an ordinary text mark: it can be moved or deleted.
     */
    private fun timeStamp(original: File): TextMark {
        val taken = runCatching {
            val exif = ExifInterface(original.path)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            raw?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
        }.getOrNull() ?: original.lastModified()
        val label = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(taken))
        val mark = TextMark(label, 0f, 0f, STAMP_SIZE, STAMP_COLOR, bold = true)
        // Its half-width on a page 1000 wide, to tuck it into the corner whatever its length.
        val (halfWidth, _) = PageRenderer.textHalfSize(mark, 1000f)
        return mark.copy(x = 1f - STAMP_MARGIN - halfWidth / 1000f, y = 1f - STAMP_MARGIN * 1.4f)
    }

    /**
     * Turns the automatic clean-ups on for [pageIds] as well: fingers, show-through and stains
     * straight away, handwriting once each page's print is found.
     */
    /**
     * Gives [pageIds] the clean-up switches of [from], on and off alike, as Filters' "Apply to"
     * gives them its look. Brush strokes stay with their page: they fit only its picture.
     * Handwriting removal is turned on once each page's print is found.
     */
    fun copyCleanup(pageIds: List<Long>, from: PageEdits) {
        cleanupLater(pageIds) {
            for (id in pageIds) {
                val edits = db.pages().get(id)?.let { EditsJson.read(it.editsJson) } ?: continue
                val wanted = edits.copy(
                    removePenInk = edits.removePenInk && from.removePenInk,
                    removeFingers = from.removeFingers,
                    removeShowThrough = from.removeShowThrough,
                    removeStains = from.removeStains,
                    fillEdges = from.fillEdges,
                )
                if (wanted != edits) setEdits(id, wanted)
            }
            if (from.removePenInk) removeHandwriting(pageIds)
        }
    }

    /**
     * Turns on handwriting removal for new pages once their print is found (a few seconds a page,
     * in the background). A page edited meanwhile is left as the person has it.
     */
    private fun removeHandwritingLater(pageIds: List<Long>) = cleanupLater(pageIds) { removeHandwriting(pageIds) }

    private suspend fun removeHandwriting(pageIds: List<Long>) {
        for (id in pageIds) {
            val entity = db.pages().get(id) ?: continue
            val edits = EditsJson.read(entity.editsJson)
            if (edits.removePenInk) continue
            // Not on a photo of a person or a scene: it's made for paper, and it would blot
            // whatever it took for ink. It can still be turned on by hand in Clean up.
            if (!mostlyPaper(entity)) continue
            val printed = runCatching { text.printedAreas(entity.originalPath, edits) }
                .onFailure { Log.w(TAG, "Finding print on page $id failed", it) }
                .getOrNull() ?: continue
            if (db.pages().get(id)?.editsJson != entity.editsJson) continue
            setEdits(id, edits.copy(removePenInk = true, printed = printed, printByLook = true, printFinder = PRINT_FINDER))
        }
    }

    /** Whether a page is mostly paper (see [PageRenderer.paperShare]), judged on its straightened render if there is one. */
    private suspend fun mostlyPaper(page: PageEntity): Boolean = withContext(Dispatchers.Default) {
        val path = page.renderPath?.takeIf { File(it).exists() } ?: page.originalPath
        runCatching {
            val bitmap = PageRenderer.loadUpright(path, PAPER_CHECK_SIDE)
            PageRenderer.paperShare(bitmap).also { bitmap.recycle() } >= MIN_PAPER
        }.getOrDefault(true)
    }

    /**
     * Combines [documentIds], in that order, into one new document of copied pages, then moves
     * the originals to Trash, so the merge can be undone with [unmerge]. Returns the new id.
     */
    suspend fun merge(documentIds: List<Long>, title: String): Long {
        require(documentIds.size >= 2)
        val first = db.documents().getRow(documentIds.first())
        val now = System.currentTimeMillis()
        val target = db.documents().insert(
            DocumentEntity(title = title, folderId = first?.folderId, createdAt = now, updatedAt = now),
        )
        val copies = copyPages(documentIds.flatMap { db.pages().forDocument(it) }, target)
        db.withTransaction {
            copies.forEach { db.pages().insert(it) }
            documentIds.forEach { db.documents().setDeletedAt(it, now) }
        }
        return target
    }

    /** Copies of [pages] (files and all, text included) belonging to [target], in order. */
    private suspend fun copyPages(pages: List<PageEntity>, target: Long): List<PageEntity> = withContext(Dispatchers.IO) {
        pages.mapIndexed { index, page ->
            val from = File(page.originalPath).parentFile!!
            val to = File(docsDir, "$target/${UUID.randomUUID()}")
            from.copyRecursively(to, overwrite = true)
            fun moved(path: String?) = path?.let { File(to, File(it).name).path }
            page.copy(
                id = 0,
                documentId = target,
                position = index,
                originalPath = moved(page.originalPath)!!,
                renderPath = moved(page.renderPath),
                thumbPath = moved(page.thumbPath),
            )
        }
    }

    /** A new document holding copies of [pageIds]; the source document is left as it is. */
    suspend fun extractPages(documentId: Long, pageIds: List<Long>, title: String): Long {
        val source = db.documents().getRow(documentId)
        val chosen = db.pages().forDocument(documentId).filter { it.id in pageIds }
        require(chosen.isNotEmpty())
        val now = System.currentTimeMillis()
        val target = db.documents().insert(
            DocumentEntity(
                title = title,
                folderId = source?.folderId,
                createdAt = now,
                updatedAt = now,
                kind = source?.kind ?: DocumentKind.Document.name,
            ),
        )
        val copies = copyPages(chosen, target)
        db.withTransaction { copies.forEach { db.pages().insert(it) } }
        return target
    }

    /**
     * A new document with each numbered question of [documentId] on a page of its own, for doing
     * again: cut from the page it's on (see [Questions]), and a question running onto the next
     * page gets that part as a page too. Pages not read yet are read first. Null if fewer than
     * two questions are found.
     */
    suspend fun splitQuestions(documentId: Long, title: String): Long? {
        text.readDocument(documentId)
        val pages = db.pages().forDocument(documentId)
        val words = pages.map { visibleWords(it) }
        val starts = Questions.starts(words)
        if (starts.isEmpty()) return null
        fun textTop(page: Int) = words[page].minOfOrNull { it.top } ?: 0f
        fun textBottom(page: Int) = words[page].maxOfOrNull { it.bottom } ?: 1f
        // Each question's stretch of a page: which page, and from where to where down it.
        val parts = mutableListOf<Triple<Int, Float, Float>>()
        for ((i, start) in starts.withIndex()) {
            val next = starts.getOrNull(i + 1)
            val pad = start.lineHeight * QUESTION_PAD
            for (p in start.page..(next?.page ?: start.page)) {
                val top = if (p == start.page) start.top - pad else textTop(p) - pad
                val bottom = if (next != null && p == next.page) next.top - next.lineHeight * QUESTION_PAD else textBottom(p) + pad
                if (bottom - top > MIN_QUESTION) parts += Triple(p, top.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
            }
        }
        val source = db.documents().getRow(documentId)
        val now = System.currentTimeMillis()
        val target = db.documents().insert(
            DocumentEntity(title = title, folderId = source?.folderId, createdAt = now, updatedAt = now, kind = source?.kind ?: DocumentKind.Document.name),
        )
        val copies = copyPages(parts.map { pages[it.first] }, target).mapIndexed { i, copy ->
            val (_, top, bottom) = parts[i]
            // The whole page's render and thumbnail are left behind: each part is rendered afresh.
            copy.copy(editsJson = EditsJson.write(cutOut(EditsJson.read(copy.editsJson), top, bottom)), ocrText = null, renderPath = null, thumbPath = null)
        }
        db.withTransaction { copies.forEach { db.pages().insert(it) } }
        // What was read off the whole page doesn't fit the part: each is read again.
        db.pages().forDocument(target).forEach { text.forget(it.id) }
        renderDocument(target)
        text.scheduleIfWanted(target)
        return target
    }

    /**
     * [edits] for the stretch of its page from [top] to [bottom] (page coordinates, full width):
     * the crop is moved in to that stretch, and what was drawn on the whole page is left behind.
     */
    private fun cutOut(edits: PageEdits, top: Float, bottom: Float): PageEdits {
        val corners = listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f)).map { c ->
            // Where this corner of the new crop's square sits on the new page as shown...
            val shown = QuadGeometry.sourceToPage(edits.quad, edits.rotation, QuadGeometry.pageToSource(edits.quad, 0, c))
            // ...and so on the old page: the same across, the new page's height squeezed into top..bottom.
            QuadGeometry.pageToSource(edits, Corner(shown.x, top + shown.y * (bottom - top)))
        }
        return edits.copy(
            quad = Quad(corners[0], corners[1], corners[2], corners[3]),
            aspect = null,
            flatten = false,
            curve = null,
            marks = emptyList(),
            texts = emptyList(),
            erase = emptyList(),
            keep = emptyList(),
            removePenInk = false,
            printed = null,
        )
    }

    /**
     * Pages [pageIds] of [documentId], photos of overlapping parts of something too big for one
     * shot (a poster, a plan), joined into one new page put just before the first of them; the
     * parts stay, to delete once the joined page looks right. Each is joined as photographed,
     * whatever its crop. Returns the new page's id, or null if they don't all overlap.
     */
    suspend fun joinPages(documentId: Long, pageIds: List<Long>): Long? {
        val parts = db.pages().forDocument(documentId).filter { it.id in pageIds }
        if (parts.size < 2) return null
        val file = File(context.cacheDir, "join-${UUID.randomUUID()}.jpg")
        val joined = withContext(Dispatchers.Default) { PageRenderer.joinPhotos(parts.map { it.originalPath }, file) }
        if (!joined) {
            file.delete()
            return null
        }
        addPages(documentId, listOf(CapturedImage(file, liveQuad = null)))
        val pages = db.pages().forDocument(documentId)
        val added = pages.last()
        movePage(documentId, pages.lastIndex, pages.indexOfFirst { it.id == parts.first().id })
        return added.id
    }

    /** Turns pages a quarter clockwise each. */
    suspend fun rotatePages(pageIds: List<Long>) {
        pageIds.forEach { id ->
            val page = db.pages().get(id) ?: return@forEach
            setEdits(id, EditsJson.read(page.editsJson).rotatedClockwise())
        }
    }

    suspend fun deletePages(pageIds: List<Long>) = pageIds.forEach { deletePage(it) }

    /** Undoes [merge]: the merged copy goes, the originals come back. */
    suspend fun unmerge(mergedId: Long, originalIds: List<Long>) {
        originalIds.forEach { db.documents().setDeletedAt(it, null) }
        deleteForever(mergedId)
    }

    /** Review is done with [documentId]: read its text now rather than after the delay. */
    suspend fun finishedEditing(documentId: Long) = text.scheduleIfWanted(documentId)

    /** Copies picked images into private storage, then adds them like camera captures. */
    suspend fun importImages(
        uris: List<Uri>,
        title: String,
        documentId: Long? = null,
        kind: DocumentKind = DocumentKind.Document,
    ): Long {
        val staged = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "import").apply { mkdirs() }
            uris.flatMap { uri ->
                runCatching {
                    if (isPdf(uri)) {
                        PdfImport.pages(context, uri, dir).map { CapturedImage(it.file, liveQuad = null, flat = true, words = it.text) }
                    } else if (isOffice(uri)) {
                        val file = context.contentResolver.openInputStream(uri)!!.use { OfficeReader.read(it) }
                        OfficePages.render(file, dir).map { CapturedImage(it.file, liveQuad = null, flat = true, words = it.text) }
                    } else {
                        val file = File(dir, "${UUID.randomUUID()}.jpg")
                        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                        listOf(CapturedImage(file, liveQuad = null))
                    }
                }.onFailure { Log.w(TAG, "Can't read $uri", it) }.getOrDefault(emptyList())
            }
        }
        require(staged.isNotEmpty()) { "None of the picked files could be read" }
        return if (documentId == null) {
            createDocument(title, folderId = null, staged, kind)
        } else {
            addPages(documentId, staged)
            documentId
        }
    }

    suspend fun setEdits(pageId: Long, requested: PageEdits) {
        val entity = db.pages().get(pageId)
        val before = entity?.let { EditsJson.read(it.editsJson) }
        val edits = entity?.let { withCurve(it.originalPath, requested, before) } ?: requested
        db.pages().setEdits(pageId, EditsJson.write(edits))
        // Word boxes follow the crop and rotation, and clean-up removes text; the look, sliders
        // and drawn marks don't change what's read.
        val reread = before != null && (
            before.quad != edits.quad || before.rotation != edits.rotation ||
                before.erase != edits.erase || before.keep != edits.keep ||
                before.removePenInk != edits.removePenInk || before.removeFingers != edits.removeFingers ||
                before.removeShowThrough != edits.removeShowThrough || before.removeStains != edits.removeStains ||
                before.fillEdges != edits.fillEdges ||
                before.redactions != edits.redactions ||
                before.flatten != edits.flatten || before.curve != edits.curve || before.descreen != edits.descreen
            )
        if (reread) {
            text.forget(pageId)
            db.pages().get(pageId)?.let { text.scheduleIfWanted(it.documentId) }
        }
        val page = db.pages().get(pageId) ?: return
        db.documents().touch(page.documentId, System.currentTimeMillis())
        renderPage(page.copy(editsJson = EditsJson.write(edits)))
    }

    suspend fun deletePage(pageId: Long) {
        val page = db.pages().get(pageId) ?: return
        db.withTransaction {
            db.pages().delete(pageId)
            db.pages().forDocument(page.documentId).forEachIndexed { index, p -> db.pages().setPosition(p.id, index) }
        }
        withContext(Dispatchers.IO) { File(page.originalPath).parentFile?.deleteRecursively() }
    }

    suspend fun movePage(documentId: Long, from: Int, to: Int) {
        db.withTransaction {
            val list = db.pages().forDocument(documentId).toMutableList()
            if (from !in list.indices || to !in list.indices) return@withTransaction
            list.add(to, list.removeAt(from))
            list.forEachIndexed { index, p -> db.pages().setPosition(p.id, index) }
        }
    }

    /** Waits until every page of [documentId] has a current render. */
    suspend fun awaitRenders(documentId: Long) {
        db.pages().forDocument(documentId).forEach { listed ->
            // Clean-up on its way changes the page and renders it again, deleting the render an
            // export would be reading.
            cleanupJobs[listed.id]?.join()
            renderJobs[listed.id]?.join()
            val page = db.pages().get(listed.id) ?: return@forEach
            if (page.renderPath == null || !File(page.renderPath).exists()) renderNow(page)
        }
    }

    /** [block], run in the background, counted as clean-up on its way to [pageIds]; see [awaitRenders]. */
    private fun cleanupLater(pageIds: List<Long>, block: suspend () -> Unit) {
        val job = scope.launch { block() }
        pageIds.forEach { id ->
            // Clean-up already on its way to the page is waited for too.
            val tracked = cleanupJobs.compute(id) { _, before ->
                if (before == null || before.isCompleted) job else scope.launch { before.join(); job.join() }
            }!!
            tracked.invokeOnCompletion { cleanupJobs.remove(id, tracked) }
        }
    }

    fun renderDocument(documentId: Long) {
        scope.launch {
            db.documents().setState(documentId, ProcessingState.Enhancing.name)
            db.pages().forDocument(documentId).filter { it.renderPath == null }.map { renderPage(it) }.forEach { it.join() }
            db.documents().setState(documentId, ProcessingState.Idle.name)
        }
    }

    /** Re-renders [page] in the background, replacing any render already queued for it. */
    private fun renderPage(page: PageEntity): Job {
        renderJobs.remove(page.id)?.cancel()
        val job = scope.launch { renderNow(page) }
        renderJobs[page.id] = job
        job.invokeOnCompletion { renderJobs.remove(page.id, job) }
        return job
    }

    private suspend fun renderNow(entity: PageEntity) = renderPermits.withPermit {
        val page = withMeasuredBend(entity)
        val result = runCatching {
            withContext(Dispatchers.Default) {
                val source = PageRenderer.loadUpright(page.originalPath, SOURCE_SIDE)
                val render = PageRenderer.render(source, EditsJson.read(page.editsJson))
                source.recycle()
                val dir = File(page.originalPath).parentFile!!
                val stamp = System.currentTimeMillis()
                val renderFile = File(dir, "render-$stamp.jpg")
                val thumbFile = File(dir, "thumb-$stamp.webp")
                PageRenderer.saveJpeg(render, renderFile)
                PageRenderer.saveThumbnail(render, thumbFile)
                render.recycle()
                Triple(renderFile, thumbFile, renderFile.length())
            }
        }
        result.onSuccess { (render, thumb, size) ->
            // A newer edit may have landed while this one rendered; only the current recipe's output is kept.
            val current = db.pages().get(page.id)
            if (current == null || current.editsJson != page.editsJson) {
                render.delete()
                thumb.delete()
                return@withPermit
            }
            db.pages().setRender(page.id, render.path, thumb.path, size)
            withContext(Dispatchers.IO) {
                render.parentFile?.listFiles()
                    ?.filter { (it.name.startsWith("render-") || it.name.startsWith("thumb-")) && it != render && it != thumb }
                    ?.forEach(File::delete)
            }
        }
        result.onFailure {
            Log.e(TAG, "Render failed for page ${page.id}", it)
            db.documents().setState(page.documentId, ProcessingState.Failed.name)
        }
    }

    /** A flattened page whose bend isn't known (measured the old way, say) is measured first. */
    private suspend fun withMeasuredBend(page: PageEntity): PageEntity {
        val edits = EditsJson.read(page.editsJson)
        if (!edits.flatten || edits.curve != null) return page
        val json = EditsJson.write(edits.copy(curve = withContext(Dispatchers.Default) { measureCurve(page.originalPath, edits) }))
        if (db.pages().get(page.id)?.editsJson != page.editsJson) return page
        db.pages().setEdits(page.id, json)
        return page.copy(editsJson = json)
    }

    /**
     * Measures the page's bend when flattening is on and it hasn't been measured for this crop;
     * a new crop or shape needs a new measurement. Flattening off forgets it.
     */
    private suspend fun withCurve(originalPath: String, edits: PageEdits, before: PageEdits?): PageEdits {
        if (!edits.flatten) return edits.copy(curve = null)
        val recropped = before != null && (before.quad != edits.quad || before.aspect != edits.aspect)
        if (edits.curve != null && !recropped) return edits
        val curve = withContext(Dispatchers.Default) { measureCurve(originalPath, edits) }
        return edits.copy(curve = curve)
    }

    private fun measureCurve(originalPath: String, edits: PageEdits): PageCurve =
        runCatching {
            val source = PageRenderer.loadUpright(originalPath, MEASURE_SIDE)
            PageRenderer.measureCurve(source, edits, PageRenderer.focal35(originalPath)).also { source.recycle() }
        }.onFailure { Log.w(TAG, "Couldn't measure the page's bend", it) }.getOrDefault(PageCurve(emptyList()))

    /**
     * Moves the capture into the document folder and works out where the page is in it. A book
     * spread becomes two pages, each pointing at its own copy of the photo.
     */
    private fun isPdf(uri: Uri): Boolean =
        context.contentResolver.getType(uri) == "application/pdf" ||
            uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true

    private fun isOffice(uri: Uri): Boolean =
        OfficeReader.handles(displayName(uri) ?: uri.lastPathSegment, context.contentResolver.getType(uri))

    /** The name the file was given, from whatever app holds it. */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Moves a ready page into the document's folder as its original. */
    private fun adopt(documentId: Long, image: CapturedImage): File {
        val original = newOriginal(documentId)
        if (!image.file.renameTo(original)) {
            image.file.copyTo(original, overwrite = true)
            image.file.delete()
        }
        return original
    }

    private fun prepare(documentId: Long, image: CapturedImage, kind: DocumentKind): List<Pair<File, PageEdits>> {
        val original = newOriginal(documentId)
        if (!image.file.renameTo(original)) {
            image.file.copyTo(original, overwrite = true)
            image.file.delete()
        }
        PageRenderer.ensureLoaded()
        val preview = runCatching { PageRenderer.loadUpright(original.path, DETECT_SIDE) }.getOrNull()
        val quad = image.quad ?: preview?.let { detect(it, kind, image.liveQuad) } ?: image.liveQuad ?: Quad.Full

        return when (kind) {
            DocumentKind.Document -> listOf(original to PageEdits(quad = quad))
            DocumentKind.Whiteboard -> listOf(original to PageEdits(quad = quad, filter = PageFilter.Board))
            // Slides keep their colors and pictures; a screen's own pattern is taken out, and the
            // shape is squared up to 16:9, 16:10 or 4:3 when it's near one.
            DocumentKind.Slides -> {
                val shape = preview?.let { QuadGeometry.warpSize(quad, it.width, it.height) }?.let { (w, h) -> SlideShape.snap(w, h) }
                listOf(original to PageEdits(quad = quad, filter = PageFilter.Original, descreen = true, contrast = SLIDE_CONTRAST, aspect = shape))
            }
            // Cards are photos and colored backgrounds, which Auto would bleach to "paper" white.
            // A card often lies turned in the photo: its text says which way up it goes.
            DocumentKind.IdCard -> listOf(original to PageEdits(quad = quad, filter = PageFilter.Original, aspect = ID1_ASPECT, rotation = textTurn(original, quad) ?: 0))
            DocumentKind.Book -> {
                val halves = preview?.let { runCatching { BookSplitter.split(it, quad) }.getOrNull() }
                    ?: QuadGeometry.splitInHalf(quad)
                // A book photographed turned, or upside down, has its pages turned too: its text
                // says which way up it is, and the pages are turned to match and put in reading
                // order, the one on the left once it's upright first.
                val turn = textTurn(original, quad) ?: 0
                val (first, second) = BookPages.inReadingOrder(halves, turn)
                val copy = newOriginal(documentId)
                original.copyTo(copy, overwrite = true)
                // Book pages curl toward the spine, so they're flattened from the start.
                listOf(original to first, copy to second).map { (file, half) ->
                    val edits = PageEdits(quad = half, flatten = true)
                    file to edits.copy(curve = measureCurve(file.path, edits), rotation = turn)
                }
            }
        }.also { preview?.recycle() }
    }

    /** Finds the page in a photo the way saving would; null when nothing page-shaped is there. */
    /** The page's outline in the photo [file]; [seen] is where the camera showed it at capture. */
    suspend fun findPage(file: File, kind: DocumentKind, seen: Quad? = null): Quad? = withContext(Dispatchers.Default) {
        PageRenderer.ensureLoaded()
        val preview = runCatching { PageRenderer.loadUpright(file.path, DETECT_SIDE) }.getOrNull() ?: return@withContext null
        detect(preview, kind, seen).also { preview.recycle() }
    }

    // Kept for the app's life and used by one detection at a time: its model takes a moment to load.
    private val pageNet by lazy { runCatching { PageNet(context) }.getOrNull() }
    private val cardNet by lazy { runCatching { PageNet(context, PageNet.CARDS) }.getOrNull() }
    private val cornerNet by lazy { runCatching { CornerNet(context) }.getOrNull() }

    // Reads which way up a book's or a card's text is; loaded the first time one is saved.
    private val turnReader by lazy { runCatching { PaddleReader(context) }.getOrNull() }

    /** Clockwise quarter turns that make what's in [quad] of [original] read upright; null if unsure. */
    private fun textTurn(original: File, quad: Quad): Int? = runCatching {
        val reader = turnReader ?: return null
        val source = PageRenderer.loadUpright(original.path, TURN_SIDE)
        val flat = PageRenderer.straighten(source, PageEdits(quad = quad, filter = PageFilter.Original), TURN_SIDE)
        source.recycle()
        synchronized(reader) { reader.upright(flat) }.also { flat.recycle() }
    }.getOrNull()

    private fun detect(preview: android.graphics.Bitmap, kind: DocumentKind, seen: Quad? = null): Quad? = runCatching {
        val finder = if (kind == DocumentKind.IdCard) cardNet else pageNet
        val detector = DocumentDetector(
            angleTolerance = if (kind == DocumentKind.Whiteboard || kind == DocumentKind.Slides) 50.0 else 35.0,
            keepSpreads = kind == DocumentKind.Book,
            cards = kind == DocumentKind.IdCard,
            finder = finder?.let { net -> PageFinder { gray -> synchronized(net) { net.pageMask(gray) } } },
            cornerFinder = cornerNet?.takeIf { kind == DocumentKind.Document || kind == DocumentKind.IdCard }
                ?.let { net -> CornerFinder { gray -> synchronized(net) { net.corners(gray) } } },
        )
        detector.detect(preview, seen).also { detector.release() }
    }.getOrNull()

    private fun newOriginal(documentId: Long): File =
        File(File(docsDir, "$documentId/${UUID.randomUUID()}").apply { mkdirs() }, "original.jpg")

    /**
     * Writes the document as a PDF to [out], with the page size and file size from Settings.
     * Renders any page that isn't ready yet first. A [password] encrypts it with AES-256.
     */
    suspend fun writePdf(documentId: Long, out: OutputStream, password: String? = null, quality: PdfQuality? = null) {
        awaitRenders(documentId)
        val prefs = settings.settings.first()
        val row = db.documents().getRow(documentId)
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "pdf-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val pages = db.pages().forDocument(documentId).mapNotNull { listed ->
                    // A page edited meanwhile gets a new render and its old one is deleted: the
                    // PDF reads its own copy, taken from the page as it is now.
                    var page = listed
                    var copy: File? = null
                    repeat(RENDER_TRIES) {
                        if (copy != null) return@repeat
                        page = db.pages().get(listed.id) ?: return@mapNotNull null
                        val render = page.renderPath?.let(::File) ?: return@mapNotNull null
                        copy = runCatching { render.copyTo(File(temp, "${UUID.randomUUID()}.jpg")) }.getOrNull()
                    }
                    val render = copy ?: return@mapNotNull null.also { Log.w(TAG, "No render of page ${listed.id} for the PDF") }
                    PdfPage(compress(render, quality ?: prefs.pdfQuality, prefs.watermark, temp), visibleWords(page))
                }
                val format = when (prefs.pdfPageFormat) {
                    PdfPageFormat.Auto -> PdfPageSize.Auto
                    PdfPageFormat.A4 -> PdfPageSize.A4
                    PdfPageFormat.Letter -> PdfPageSize.Letter
                }
                // Cards always print on a real sheet, so Auto means A4 for them.
                val writer = JpegPdfWriter(format, password)
                if (row?.kind == DocumentKind.IdCard.name) {
                    writer.writeCards(pages, out, row.title)
                } else {
                    writer.writePages(pages, out, row?.title)
                }
            } finally {
                temp.deleteRecursively()
            }
        }
    }

    /**
     * The render as it goes into an export: smaller for Small and Balanced, and with the user's
     * watermark stamped on if they set one. Best without a watermark uses the render as it is.
     */
    private fun compress(render: File, quality: PdfQuality, watermark: String, dir: File): File {
        val (side, jpeg) = when (quality) {
            PdfQuality.Small -> 1600 to 60
            PdfQuality.Balanced -> 2200 to 75
            PdfQuality.Best -> PageRenderer.RENDER_SIDE to 88
        }
        if (quality == PdfQuality.Best && watermark.isBlank()) return render
        val bitmap = PageRenderer.loadUpright(render.path, side).let {
            if (it.isMutable) it else it.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { _ -> it.recycle() }
        }
        PageRenderer.drawWatermark(bitmap, watermark)
        return File(dir, "${UUID.randomUUID()}.jpg").also {
            PageRenderer.saveJpeg(bitmap, it, jpeg)
            bitmap.recycle()
        }
    }

    /** A PDF in the share cache, named after the document. */
    /** The document as a PDF in the share cache; [quality] overrides the setting, e.g. Small to send. */
    suspend fun exportPdf(documentId: Long, password: String? = null, quality: PdfQuality? = null): File {
        val title = db.documents().getRow(documentId)?.title ?: "Scan"
        val file = File(exportsDir.apply { mkdirs() }, "${safeName(title)}.pdf")
        withContext(Dispatchers.IO) { file.outputStream().buffered().use { writePdf(documentId, it, password, quality) } }
        return file
    }

    /**
     * One PDF per document, together in a fresh folder of the share cache. Documents with the
     * same title get "Title (2).pdf" and so on, rather than overwriting each other.
     */
    suspend fun exportPdfs(documentIds: List<Long>): List<File> {
        val dir = File(exportsDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val taken = mutableSetOf<String>()
        return documentIds.map { id ->
            val title = safeName(db.documents().getRow(id)?.title ?: "Scan")
            val name = generateSequence(1) { it + 1 }
                .map { if (it == 1) "$title.pdf" else "$title ($it).pdf" }
                .first { taken.add(it.lowercase()) }
            val file = File(dir, name)
            withContext(Dispatchers.IO) { file.outputStream().buffered().use { writePdf(id, it) } }
            file
        }
    }

    /** All recognized text as a .txt in the share cache; pages separated by a blank line. */
    suspend fun exportText(documentId: Long): File {
        val title = safeName(db.documents().getRow(documentId)?.title ?: "Scan")
        val body = db.pages().forDocument(documentId)
            .mapNotNull { it.ocrText?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        return withContext(Dispatchers.IO) {
            File(exportsDir.apply { mkdirs() }, "$title.txt").apply { writeText(body) }
        }
    }

    /**
     * The document as a Word file in the share cache: the title, then each read page rebuilt
     * from where its words sit (paragraphs, headings, centred lines, letter sizes, see
     * [PageLayout]), with the page's pictures cut out and put back in reading order.
     */
    suspend fun exportDocx(documentId: Long): File {
        awaitRenders(documentId)
        val title = db.documents().getRow(documentId)?.title ?: "Scan"
        val pages = db.pages().forDocument(documentId).mapNotNull { page -> withContext(Dispatchers.Default) { layout(page) } }
        require(pages.isNotEmpty()) { "No page has been read yet" }
        return withContext(Dispatchers.IO) {
            File(exportsDir.apply { mkdirs() }, "${safeName(title)}.docx").apply {
                outputStream().buffered().use { DocxWriter.writeLayout(title, pages, it) }
            }
        }
    }

    /** One page's paragraphs and pictures in reading order; null if it hasn't been read. */
    private fun layout(page: PageEntity): List<LayoutBlock>? {
        val words = visibleWords(page)
        if (words.isEmpty()) {
            // Pages read before word boxes were kept: their text, as plain paragraphs. Not when
            // parts are blacked out, as the text would still hold them.
            val text = page.ocrText?.takeIf { it.isNotBlank() && EditsJson.read(page.editsJson).redactions.isEmpty() } ?: return null
            return DocxWriter.paragraphs(text).map { LayoutParagraph(it, ParagraphStyle.Body, false, 1f, 0f) }
        }
        val render = page.renderPath?.let(::File)?.takeIf(File::exists)?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        val areas = render?.let { bitmap ->
            runCatching { Figures.find(bitmap, words.map { android.graphics.RectF(it.left, it.top, it.right, it.bottom) }) }
                .onFailure { Log.w(TAG, "Couldn't find the pictures on page ${page.id}", it) }.getOrNull()
        }.orEmpty()
        val pictures = areas.map { area ->
            val bitmap = render!!
            val x = (area.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val y = (area.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val w = ((area.right - area.left) * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
            val h = ((area.bottom - area.top) * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
            val crop = android.graphics.Bitmap.createBitmap(bitmap, x, y, w, h)
            val jpeg = java.io.ByteArrayOutputStream().also { crop.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            LayoutPicture(jpeg, crop.width, crop.height, area.right - area.left, area.top, area.left).also { crop.recycle() }
        }
        render?.recycle()
        // Words inside a picture (a chart's labels) are part of it.
        val text = words.filterNot { w ->
            val cx = (w.left + w.right) / 2
            val cy = (w.top + w.bottom) / 2
            areas.any { it.contains(cx, cy) }
        }
        return PageLayout.blocks(text, pictures)
    }

    /**
     * The document as a PowerPoint deck: each finished page fills a slide, with its text behind
     * the picture so the deck can be searched. Covered areas stay out of the text.
     */
    suspend fun exportPptx(documentId: Long): File {
        awaitRenders(documentId)
        val title = db.documents().getRow(documentId)?.title ?: "Scan"
        val watermark = settings.settings.first().watermark
        val pages = db.pages().forDocument(documentId)
        return withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "pptx-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val slides = pages.mapNotNull { page ->
                    val render = page.renderPath?.let(::File)?.takeIf(File::exists) ?: return@mapNotNull null
                    val jpeg = compress(render, PdfQuality.Balanced, watermark, temp)
                    val size = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        .also { android.graphics.BitmapFactory.decodeFile(jpeg.path, it) }
                    val text = PageLayout.paragraphs(visibleWords(page)).joinToString("\n") { it.text }
                    PptxSlide(jpeg.readBytes(), size.outWidth, size.outHeight, text)
                }
                require(slides.isNotEmpty()) { "Nothing to export" }
                File(exportsDir.apply { mkdirs() }, "${safeName(title)}.pptx").apply {
                    outputStream().buffered().use { PptxWriter.write(title, slides, it) }
                }
            } finally {
                temp.deleteRecursively()
            }
        }
    }

    /**
     * The read pages as a spreadsheet, one sheet per page: words placed in rows and columns by
     * where they sit (see [TableLayout]), so a receipt or a table comes out as cells. Covered
     * areas stay out. Pages not read yet are skipped.
     */
    suspend fun exportXlsx(documentId: Long): File {
        val title = db.documents().getRow(documentId)?.title ?: "Scan"
        val sheets = db.pages().forDocument(documentId).mapIndexedNotNull { i, page ->
            val words = visibleWords(page)
            if (words.isEmpty()) null else "Page ${i + 1}" to TableLayout.cells(words)
        }
        require(sheets.isNotEmpty()) { "No page has been read yet" }
        return withContext(Dispatchers.IO) {
            File(exportsDir.apply { mkdirs() }, "${safeName(title)}.xlsx").apply {
                outputStream().buffered().use { XlsxWriter.write(sheets, it) }
            }
        }
    }

    /** Marks pages as answered wrong, or not, to practise them again. */
    suspend fun markWrong(pageIds: List<Long>, wrong: Boolean) = db.pages().setWrong(pageIds, wrong)

    /** Replaces a page's text with the user's corrections. Boxes for the PDF stay as read. */
    suspend fun setText(pageId: Long, value: String) = db.pages().setText(pageId, value)

    /** The page's last translation, while its text is still what was translated. */
    suspend fun translation(page: Page): PageTranslation? = withContext(Dispatchers.IO) {
        page.text?.let { PageTranslation.read(PageTranslation.file(page.originalPath), it) }
    }

    /**
     * The text of the page's paragraphs as they sit on it, to translate and set back in their
     * places with [drawTranslation]; empty without word boxes.
     */
    suspend fun paragraphsOnPage(page: Page): List<String> = placedParagraphs(page).map { it.text }

    private suspend fun placedParagraphs(page: Page): List<LayoutParagraph> = withContext(Dispatchers.IO) {
        val entity = db.pages().get(page.id) ?: return@withContext emptyList()
        PageLayout.paragraphs(visibleWords(entity))
    }

    /**
     * A picture of [page] with [texts], one for each of its [paragraphsOnPage], written over them
     * in their places (see [TranslatedPage]), kept in the cache; null when the page has no
     * picture yet or its paragraphs have changed.
     */
    suspend fun drawTranslation(page: Page, texts: List<String>): File? {
        val paragraphs = placedParagraphs(page).takeIf { it.size == texts.size && it.isNotEmpty() } ?: return null
        return drawTranslation(page, paragraphs, texts)
    }

    private suspend fun drawTranslation(page: Page, paragraphs: List<LayoutParagraph>, texts: List<String>): File? = withContext(Dispatchers.Default) {
        val render = page.renderPath?.let(::File)?.takeIf(File::exists) ?: return@withContext null
        val bitmap = android.graphics.BitmapFactory.decodeFile(render.path) ?: return@withContext null
        val drawn = try { TranslatedPage.draw(bitmap, paragraphs, texts) } finally { bitmap.recycle() }
        val dir = File(context.cacheDir, "translated").apply { mkdirs() }
        // A new name for each translation, so a picture shown before isn't taken for it.
        dir.listFiles { f -> f.name.startsWith("${page.id}-") }?.forEach(File::delete)
        val file = File(dir, "${page.id}-${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { drawn.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            drawn.recycle()
        }
        file
    }

    /** [pictures] of translated pages ready to share, named after the document and [language]. */
    suspend fun exportTranslatedPictures(documentId: Long, pictures: List<File>, language: String): List<File> {
        val watermark = settings.settings.first().watermark
        val title = safeName("${db.documents().getRow(documentId)?.title ?: "Scan"} ($language)")
        return withContext(Dispatchers.IO) {
            val dir = File(exportsDir, UUID.randomUUID().toString()).apply { mkdirs() }
            pictures.filter(File::exists).mapIndexed { index, picture ->
                val name = if (pictures.size == 1) "$title.jpg" else "$title ${index + 1}.jpg"
                if (watermark.isBlank()) {
                    picture.copyTo(File(dir, name), overwrite = true)
                } else {
                    compress(picture, PdfQuality.Best, watermark, dir).renameTo(File(dir, name))
                    File(dir, name)
                }
            }
        }
    }

    /** Keeps [translation] of the page's current text, in place of any earlier one. */
    suspend fun keepTranslation(page: Page, translation: PageTranslation) = withContext(Dispatchers.IO) {
        page.text?.let { PageTranslation.write(PageTranslation.file(page.originalPath), it, translation) }
    }

    /** Where the printed words are on [page] as [edits] shape it; see [TextReader.printedAreas]. */
    suspend fun printedAreas(page: Page, edits: PageEdits): List<Area> = text.printedAreas(page.originalPath, edits)

    /** Reads the text now, even if automatic reading is off. */
    fun readNow(documentId: Long) = text.readNow(documentId)

    /** How far reading [documentId]'s text has got, or null when it isn't being read. */
    fun readProgress(documentId: Long): Flow<ReadProgress?> = text.progress.map { it[documentId] }

    /** One JPEG per page in the share cache: "Title 1.jpg", "Title 2.jpg"… */
    suspend fun exportJpegs(documentId: Long): List<File> {
        awaitRenders(documentId)
        val watermark = settings.settings.first().watermark
        val title = safeName(db.documents().getRow(documentId)?.title ?: "Scan")
        val pages = db.pages().forDocument(documentId)
        return withContext(Dispatchers.IO) {
            val dir = File(exportsDir, UUID.randomUUID().toString()).apply { mkdirs() }
            pages.mapIndexedNotNull { index, page ->
                val render = page.renderPath?.let(::File)?.takeIf(File::exists) ?: return@mapIndexedNotNull null
                val name = if (pages.size == 1) "$title.jpg" else "$title ${index + 1}.jpg"
                if (watermark.isBlank()) {
                    render.copyTo(File(dir, name), overwrite = true)
                } else {
                    compress(render, PdfQuality.Best, watermark, dir).renameTo(File(dir, name))
                    File(dir, name)
                }
            }
        }
    }

    /**
     * Every page, one under the next, as a single tall JPEG: easy to scroll through in a chat.
     * Pages share the narrowest page's width (at most [LONG_WIDTH]) with a thin gap between.
     */
    /**
     * An ID photo of [size] from the photo of page [index] (taken or imported as it is, before any
     * crop or look), on [background], and a 4 × 6 in sheet of copies to print. Null when there's
     * no face to go by.
     */
    suspend fun exportIdPhoto(documentId: Long, index: Int, size: IdPhotoSize, background: IdBackground): List<File>? {
        val page = db.pages().forDocument(documentId).getOrNull(index) ?: return null
        val title = safeName(db.documents().getRow(documentId)?.title ?: "Scan")
        val turn = EditsJson.read(page.editsJson).rotation
        return withContext(Dispatchers.Default) {
            val photo = PageRenderer.loadUpright(page.originalPath, ID_SOURCE_SIDE).let { source ->
                if (((turn % 360) + 360) % 360 == 0) {
                    source
                } else {
                    val m = android.graphics.Matrix().apply { postRotate(turn.toFloat()) }
                    android.graphics.Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true).also { source.recycle() }
                }
            }
            val made = IdPhoto.fromPhoto(photo, size, background)
            photo.recycle()
            made ?: return@withContext null
            val dir = exportsDir.apply { mkdirs() }
            listOf(made.first to "$title ID photo.jpg", made.second to "$title ID photos 4x6 in.jpg").map { (jpeg, name) ->
                File(dir, name).apply { writeBytes(jpeg) }
            }
        }
    }

    /** The [PageSignature] of the page inside [quad] of the photo [file]; null if it can't be read. */
    suspend fun signature(file: File, quad: Quad): FloatArray? = withContext(Dispatchers.Default) {
        runCatching {
            val photo = PageRenderer.loadUpright(file.path, SIGNATURE_SIDE)
            PageSignature.of(photo, quad).also { photo.recycle() }
        }.getOrNull()
    }

    /** Signatures of [documentId]'s pages as they are, in order; null for a page not rendered yet. */
    suspend fun pageSignatures(documentId: Long): List<FloatArray?> = db.pages().forDocument(documentId).map { page ->
        page.renderPath?.let(::File)?.takeIf(File::exists)?.let { signature(it, Quad.Full) }
    }

    suspend fun exportLongImage(documentId: Long): File {
        val pages = exportJpegs(documentId)
        require(pages.isNotEmpty()) { "Nothing to export" }
        val title = safeName(db.documents().getRow(documentId)?.title ?: "Scan")
        return withContext(Dispatchers.IO) {
            val sizes = pages.map { f ->
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                    android.graphics.BitmapFactory.decodeFile(f.path, it)
                }
            }
            val width = minOf(LONG_WIDTH, sizes.minOf { it.outWidth })
            val heights = sizes.map { (it.outHeight.toLong() * width / it.outWidth).toInt() }
            // Very long documents are scaled down rather than made too tall to open.
            val total = heights.sum() + LONG_GAP * (pages.size - 1)
            val fit = minOf(1.0, LONG_MAX_HEIGHT.toDouble() / total)
            val w = (width * fit).toInt().coerceAtLeast(1)
            val gap = (LONG_GAP * fit).toInt()
            val hs = heights.map { (it * fit).toInt().coerceAtLeast(1) }
            val out = android.graphics.Bitmap.createBitmap(w, hs.sum() + gap * (pages.size - 1), android.graphics.Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(LONG_GAP_COLOR)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            var y = 0
            pages.forEachIndexed { i, f ->
                val page = android.graphics.BitmapFactory.decodeFile(f.path) ?: return@forEachIndexed
                canvas.drawBitmap(page, null, android.graphics.Rect(0, y, w, y + hs[i]), paint)
                page.recycle()
                y += hs[i] + gap
            }
            val file = File(pages.first().parentFile, "$title (long).jpg")
            file.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            out.recycle()
            pages.forEach(File::delete)
            file
        }
    }

    /** Clears old share files; nothing in the export cache outlives a launch. */
    fun clearExports() {
        scope.launch(Dispatchers.IO) { exportsDir.deleteRecursively() }
    }

    suspend fun deleteForever(documentId: Long) {
        db.documents().delete(documentId)
        withContext(Dispatchers.IO) { File(docsDir, documentId.toString()).deleteRecursively() }
    }

    suspend fun emptyTrash() {
        db.documents().trashedIds().forEach { deleteForever(it) }
    }

    /** Finishes documents left part-made when the app last stopped: pages not yet rendered. */
    suspend fun finishInterrupted() {
        db.documents().idsInState(ProcessingState.Enhancing.name).forEach { renderDocument(it) }
        redrawCleanedPages()
    }

    /**
     * Pages with handwriting removed are drawn again, once, when the way it's done has changed
     * ([CLEANUP_VERSION]): their renders were made the old way, and stay until an edit otherwise.
     * Print an older finder found is found again first ([PRINT_FINDER]). In the background, a
     * page at a time.
     */
    private fun redrawCleanedPages() {
        scope.launch {
            val mark = File(context.filesDir, "cleanup-version")
            val done = withContext(Dispatchers.IO) { mark.takeIf(File::exists)?.readText()?.trim()?.toIntOrNull() } ?: 0
            if (done >= CLEANUP_VERSION) return@launch
            for (page in db.pages().withEdits("\"removePenInk\":true")) {
                val edits = EditsJson.read(page.editsJson)
                if (!edits.removePenInk) continue
                val stale = edits.printed == null || !edits.printByLook || edits.printFinder < PRINT_FINDER
                val printed = if (stale) runCatching { text.printedAreas(page.originalPath, edits) }.getOrNull() else null
                if (printed != null && db.pages().get(page.id)?.editsJson == page.editsJson) {
                    setEdits(page.id, edits.copy(printed = printed, printByLook = true, printFinder = PRINT_FINDER))
                    renderJobs[page.id]?.join()
                } else {
                    renderPage(page).join()
                }
            }
            withContext(Dispatchers.IO) { mark.writeText(CLEANUP_VERSION.toString()) }
        }
    }

    /** Deletes documents that have sat in Trash for more than 30 days. */
    suspend fun purgeTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 86_400_000L
        db.documents().trashedBefore(cutoff).forEach { deleteForever(it) }
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(80).ifEmpty { "Scan" }

    companion object {
        const val TRASH_DAYS = 30
        /** What can be brought in as pages: PDFs, Word, PowerPoint and Excel files, and pictures. */
        val IMPORTABLE = arrayOf("application/pdf", "image/*") + OfficeReader.MIME_TYPES
        /** ISO/IEC 7810 ID-1: 85.60 × 53.98 mm. */
        const val ID1_ASPECT = 85.60f / 53.98f
        private const val TAG = "PageStore"
        /** Originals are read at this size before warping, so the crop still has detail. */
        private const val SOURCE_SIDE = 4000
        /** ID photos are cut from the photo at up to this size: plenty for 300 dpi. */
        private const val ID_SOURCE_SIDE = 3000
        /** Plenty for a page's signature, which is a small grid. */
        private const val SIGNATURE_SIDE = 800
        /** Room left above a question and below it, in its line heights; parts thinner than [MIN_QUESTION] are dropped. */
        private const val QUESTION_PAD = 0.8f
        private const val MIN_QUESTION = 0.02f
        /** The kinds of scan that are paper with a plain margin, for filling in their edges. */
        private val PAPER_KINDS = setOf(DocumentKind.Document, DocumentKind.Book, DocumentKind.Whiteboard)
        /** Automatic handwriting removal waits for a page at least this much paper. */
        private const val MIN_PAPER = 0.45
        /**
         * Raised when handwriting removal changes how a page comes out, so pages already cleaned
         * are drawn again (see [redrawCleanedPages]). 1: printed rules kept on bent paper; 2: dots and signs kept; 3: signs kept from color sorting too (0.8.6).
         */
        private const val CLEANUP_VERSION = 3
        /** Times a PDF tries to copy a page's render that's being replaced; see [writePdf]. */
        private const val RENDER_TRIES = 3
        private const val PAPER_CHECK_SIDE = 800
        private const val DETECT_SIDE = 640
        /** A book's or a card's text is read at this size to tell which way up it is. */
        private const val TURN_SIDE = 1600
        private const val LONG_WIDTH = 1240
        private const val LONG_GAP = 24
        private const val LONG_GAP_COLOR = 0xFFD9D9D9.toInt()
        /** Taller than this and some apps refuse to show a picture. */
        private const val LONG_MAX_HEIGHT = 30000
        /** The date stamp: letters 2.2% of the page's width, orange like a film camera's. */
        private const val STAMP_SIZE = 0.022f
        private const val STAMP_COLOR = 0xFFF06A1D.toInt()
        private const val STAMP_MARGIN = 0.03f
        /** A photo of a lit screen comes out a little flat; this much more contrast lifts it. */
        private const val SLIDE_CONTRAST = 0.15f
        private const val MEASURE_SIDE = 2000
    }
}

private fun PageEntity.toModel() = Page(
    id = id,
    documentId = documentId,
    position = position,
    originalPath = originalPath,
    renderPath = renderPath,
    thumbPath = thumbPath,
    edits = EditsJson.read(editsJson),
    text = ocrText,
    wrong = wrong,
)
