package dev.olaf.oscanner.core.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dev.olaf.oscanner.core.data.db.DocumentEntity
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.data.db.PageEntity
import dev.olaf.oscanner.core.imaging.BookSplitter
import dev.olaf.oscanner.core.imaging.DocumentDetector
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.DocumentKind
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.ProcessingState
import dev.olaf.oscanner.core.model.Quad
import dev.olaf.oscanner.core.pdf.JpegPdfWriter
import dev.olaf.oscanner.core.pdf.PdfPage
import dev.olaf.oscanner.core.pdf.PdfPageSize
import dev.olaf.oscanner.core.model.PdfPageFormat
import dev.olaf.oscanner.core.model.PdfQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A photo waiting to become a page, with the outline the camera saw (if any). */
data class CapturedImage(val file: File, val liveQuad: Quad?)

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
    private val docsDir get() = File(context.filesDir, "docs")
    private val exportsDir get() = File(context.cacheDir, "exports")

    fun observePages(documentId: Long): Flow<List<Page>> =
        db.pages().observe(documentId).map { list -> list.map(PageEntity::toModel) }

    suspend fun pages(documentId: Long): List<Page> = db.pages().forDocument(documentId).map(PageEntity::toModel)

    suspend fun page(id: Long): Page? = db.pages().get(id)?.toModel()

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
            ),
        )
        addPages(docId, images)
        return docId
    }

    /** Appends captures to the end of [documentId] and renders them. */
    suspend fun addPages(documentId: Long, images: List<CapturedImage>) {
        val kind = kindOf(documentId)
        val prepared = withContext(Dispatchers.IO) { images.flatMap { prepare(documentId, it, kind) } }
        db.withTransaction {
            var position = db.pages().lastPosition(documentId)
            prepared.forEach { (original, edits) ->
                db.pages().insert(
                    PageEntity(
                        documentId = documentId,
                        position = ++position,
                        originalPath = original.path,
                        width = 0,
                        height = 0,
                        sizeBytes = original.length(),
                        editsJson = EditsJson.write(edits),
                    ),
                )
            }
            db.documents().touch(documentId, System.currentTimeMillis())
        }
        renderDocument(documentId)
        text.scheduleIfWanted(documentId, delay = Duration.ofSeconds(30))
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
            uris.mapNotNull { uri ->
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    CapturedImage(file, liveQuad = null)
                }.onFailure { Log.w(TAG, "Can't read $uri", it) }.getOrNull()
            }
        }
        require(staged.isNotEmpty()) { "None of the picked images could be read" }
        return if (documentId == null) {
            createDocument(title, folderId = null, staged, kind)
        } else {
            addPages(documentId, staged)
            documentId
        }
    }

    suspend fun setEdits(pageId: Long, edits: PageEdits) {
        val before = db.pages().get(pageId)?.let { EditsJson.read(it.editsJson) }
        db.pages().setEdits(pageId, EditsJson.write(edits))
        // Word boxes follow the crop and rotation; the look and sliders don't change what's read.
        if (before != null && (before.quad != edits.quad || before.rotation != edits.rotation)) {
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
        db.pages().forDocument(documentId).forEach { page ->
            renderJobs[page.id]?.join()
            if (page.renderPath == null || !File(page.renderPath).exists()) renderNow(page)
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

    private suspend fun renderNow(page: PageEntity) = renderPermits.withPermit {
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

    /**
     * Moves the capture into the document folder and works out where the page is in it. A book
     * spread becomes two pages, each pointing at its own copy of the photo.
     */
    private fun prepare(documentId: Long, image: CapturedImage, kind: DocumentKind): List<Pair<File, PageEdits>> {
        val original = newOriginal(documentId)
        if (!image.file.renameTo(original)) {
            image.file.copyTo(original, overwrite = true)
            image.file.delete()
        }
        PageRenderer.ensureLoaded()
        val preview = runCatching { PageRenderer.loadUpright(original.path, DETECT_SIDE) }.getOrNull()
        val quad = preview?.let { bitmap ->
            runCatching {
                val detector = DocumentDetector(angleTolerance = if (kind == DocumentKind.Whiteboard) 50.0 else 35.0)
                detector.detect(bitmap).also { detector.release() }
            }.getOrNull()
        } ?: image.liveQuad ?: Quad.Full

        return when (kind) {
            DocumentKind.Document -> listOf(original to PageEdits(quad = quad))
            DocumentKind.Whiteboard -> listOf(original to PageEdits(quad = quad, filter = PageFilter.Board))
            // Cards are photos and colored backgrounds, which Auto would bleach to "paper" white.
            DocumentKind.IdCard -> listOf(original to PageEdits(quad = quad, filter = PageFilter.Original, aspect = ID1_ASPECT))
            DocumentKind.Book -> {
                val (left, right) = preview?.let { runCatching { BookSplitter.split(it, quad) }.getOrNull() }
                    ?: QuadGeometry.splitInHalf(quad)
                val copy = newOriginal(documentId)
                original.copyTo(copy, overwrite = true)
                listOf(original to PageEdits(quad = left), copy to PageEdits(quad = right))
            }
        }.also { preview?.recycle() }
    }

    private fun newOriginal(documentId: Long): File =
        File(File(docsDir, "$documentId/${UUID.randomUUID()}").apply { mkdirs() }, "original.jpg")

    /**
     * Writes the document as a PDF to [out], with the page size and file size from Settings.
     * Renders any page that isn't ready yet first. A [password] encrypts it with AES-256.
     */
    suspend fun writePdf(documentId: Long, out: OutputStream, password: String? = null) {
        awaitRenders(documentId)
        val prefs = settings.settings.first()
        val row = db.documents().getRow(documentId)
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "pdf-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val pages = db.pages().forDocument(documentId).mapNotNull { page ->
                    val render = page.renderPath?.let(::File)?.takeIf(File::exists) ?: return@mapNotNull null
                    PdfPage(compress(render, prefs.pdfQuality, temp), text.words(page))
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

    /** Smaller copies of a render for the PDF; Best uses the render as it is. */
    private fun compress(render: File, quality: PdfQuality, dir: File): File {
        val (side, jpeg) = when (quality) {
            PdfQuality.Small -> 1600 to 60
            PdfQuality.Balanced -> 2200 to 75
            PdfQuality.Best -> return render
        }
        val bitmap = PageRenderer.loadUpright(render.path, side)
        return File(dir, "${UUID.randomUUID()}.jpg").also {
            PageRenderer.saveJpeg(bitmap, it, jpeg)
            bitmap.recycle()
        }
    }

    /** A PDF in the share cache, named after the document. */
    suspend fun exportPdf(documentId: Long, password: String? = null): File {
        val title = db.documents().getRow(documentId)?.title ?: "Scan"
        val file = File(exportsDir.apply { mkdirs() }, "${safeName(title)}.pdf")
        withContext(Dispatchers.IO) { file.outputStream().buffered().use { writePdf(documentId, it, password) } }
        return file
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

    /** Replaces a page's text with the user's corrections. Boxes for the PDF stay as read. */
    suspend fun setText(pageId: Long, value: String) = db.pages().setText(pageId, value)

    /** Reads the text now, even if automatic reading is off. */
    fun readNow(documentId: Long) = text.schedule(documentId)

    /** One JPEG per page in the share cache: "Title 1.jpg", "Title 2.jpg"… */
    suspend fun exportJpegs(documentId: Long): List<File> {
        awaitRenders(documentId)
        val title = safeName(db.documents().getRow(documentId)?.title ?: "Scan")
        val pages = db.pages().forDocument(documentId)
        return withContext(Dispatchers.IO) {
            val dir = File(exportsDir, UUID.randomUUID().toString()).apply { mkdirs() }
            pages.mapIndexedNotNull { index, page ->
                val render = page.renderPath?.let(::File)?.takeIf(File::exists) ?: return@mapIndexedNotNull null
                val name = if (pages.size == 1) "$title.jpg" else "$title ${index + 1}.jpg"
                render.copyTo(File(dir, name), overwrite = true)
            }
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

    /** Deletes documents that have sat in Trash for more than 30 days. */
    suspend fun purgeTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 86_400_000L
        db.documents().trashedBefore(cutoff).forEach { deleteForever(it) }
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(80).ifEmpty { "Scan" }

    companion object {
        const val TRASH_DAYS = 30
        /** ISO/IEC 7810 ID-1: 85.60 × 53.98 mm. */
        const val ID1_ASPECT = 85.60f / 53.98f
        private const val TAG = "PageStore"
        /** Originals are read at this size before warping, so the crop still has detail. */
        private const val SOURCE_SIDE = 4000
        private const val DETECT_SIDE = 640
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
)
