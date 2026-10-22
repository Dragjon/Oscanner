package dev.olaf.oscanner.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.CapturedImage
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.data.SettingsRepository
import dev.olaf.oscanner.core.imaging.PageSignature
import androidx.exifinterface.media.ExifInterface
import dev.olaf.oscanner.core.model.CameraOrientation
import dev.olaf.oscanner.core.model.ScanFixes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.DocumentKind
import dev.olaf.oscanner.core.model.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class CaptureStatus { Searching, HoldSteady, Captured }

/** What the camera is set up to scan. Each page mode makes one kind of document. */
enum class CaptureMode(val kind: DocumentKind?) {
    Document(DocumentKind.Document),
    IdCard(DocumentKind.IdCard),
    Book(DocumentKind.Book),
    Whiteboard(DocumentKind.Whiteboard),
    Slides(DocumentKind.Slides),
    /** QR codes and barcodes: read live, nothing is saved. */
    Code(null),
    ;

    companion object {
        fun of(kind: DocumentKind) = entries.first { it.kind == kind }
    }
}

data class CaptureUiState(
    /** Page outline in normalized upright-frame coordinates. */
    val quad: Quad? = null,
    /** Upright frame width / height, to place the outline over the preview. */
    val frameAspect: Float = 3f / 4f,
    val progress: Float = 0f,
    val status: CaptureStatus = CaptureStatus.Searching,
    val captures: List<CapturedImage> = emptyList(),
    val autoCapture: Boolean = true,
    val mode: CaptureMode = CaptureMode.Document,
    /** Adding pages to an existing document keeps that document's mode. */
    val modeLocked: Boolean = false,
    /** A code that was just read; scanning pauses until it's dismissed. */
    val code: ScannedCode? = null,
    val flash: Boolean = false,
    /** A photo is being taken; the shutter ignores taps until it lands. */
    val busy: Boolean = false,
    val saving: Boolean = false,
    /** A reflection on the page in most recent frames: the screen suggests tilting. */
    val glare: Boolean = false,
    /** The pages taken so far are open for a look; the camera holds off meanwhile. */
    val reviewing: Boolean = false,
    /** A word about the shot: a finger in view, too dark, a slant, and so on. */
    val hint: CaptureHint? = null,
    /** Fixes applied to every page as it's saved. */
    val fixes: ScanFixes = ScanFixes(),
    val orientation: CameraOrientation = CameraOrientation.Auto,
    /** The orientation was changed a moment ago: the status says what it is now. */
    val orientationChanged: Boolean = false,
    /** The page just taken looks like one taken before; null when it doesn't. */
    val duplicate: Duplicate? = null,
)

/**
 * A capture that looks like page [page] (1-based, counting the document's pages first). Taken
 * automatically, it was [left] out of the scan; taken with the shutter, it was kept.
 */
data class Duplicate(val capture: CapturedImage, val page: Int, val left: Boolean)

/** Where to go once captures are saved: the document and the index of its first new page. */
data class SavedScan(val documentId: Long, val firstNewPage: Int)

class CaptureViewModel(
    private val pages: PageStore,
    private val settings: SettingsRepository,
    cacheDir: File,
    /** Set when adding pages to an existing document. */
    private val documentId: Long?,
) : ViewModel() {

    private val sessionDir = File(cacheDir, "capture/${UUID.randomUUID()}")
    private val tracker = AutoCaptureTracker()
    private val hints = CaptureHints()
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val shutter = Channel<Unit>(Channel.CONFLATED)
    /** Auto-capture asks the screen to take a picture through here. */
    val shutterRequests: Flow<Unit> = shutter.receiveAsFlow()

    private val saved = Channel<SavedScan>(Channel.BUFFERED)
    val savedScans: Flow<SavedScan> = saved.receiveAsFlow()

    private var capturedAt = 0L
    /** How far the phone is turned (0, 90, 180, 270); photos come out upright for it. */
    @Volatile private var deviceTurn = 0
    @Volatile private var sensedTurn = 0
    /** Glare in each of the last few frames, newest last, so the hint doesn't flicker. */
    private val glareFrames = ArrayDeque<Boolean>()

    init {
        viewModelScope.launch {
            settings.settings.map { it.scanFixes }.distinctUntilChanged().collect { fixes -> _state.update { it.copy(fixes = fixes) } }
        }
        viewModelScope.launch {
            settings.settings.map { it.cameraOrientation }.distinctUntilChanged().collect { o -> _state.update { it.copy(orientation = o) } }
        }
        if (documentId != null) {
            _state.update { it.copy(modeLocked = true) }
            viewModelScope.launch {
                val kind = pages.kindOf(documentId)
                _state.update { it.copy(mode = CaptureMode.of(kind)) }
            }
        }
    }

    /** Switches mode; not while a batch is in progress, since a batch is one kind of document. */
    fun setMode(mode: CaptureMode) {
        _state.update { if (it.modeLocked || it.captures.isNotEmpty()) it else it.copy(mode = mode, code = null) }
    }

    /** Called on the analysis thread when a code is read. */
    fun onCode(code: ScannedCode) {
        _state.update { if (it.code == null && it.mode == CaptureMode.Code) it.copy(code = code) else it }
    }

    fun dismissCode() = _state.update { it.copy(code = null) }

    /** Called on the analysis thread for every frame. */
    fun onFrame(
        quad: Quad?,
        sharpness: Double,
        frameAspect: Float,
        glare: Boolean = false,
        signals: FrameSignals? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val steadyGlare = synchronized(glareFrames) {
            glareFrames.addLast(glare)
            if (glareFrames.size > GLARE_WINDOW) glareFrames.removeFirst()
            glareFrames.count { it } >= GLARE_NEEDED
        }
        tracker.steadySize = _state.value.mode == CaptureMode.Book
        val result = synchronized(tracker) { tracker.onFrame(quad, sharpness, now, _state.value.autoCapture && !_state.value.reviewing) }
        if (result.fire && !_state.value.busy) {
            autoShot = true
            shutter.trySend(Unit)
        }
        val status = when {
            now - capturedAt < CAPTURED_HOLD_MS -> CaptureStatus.Captured
            result.quad == null -> CaptureStatus.Searching
            else -> CaptureStatus.HoldSteady
        }
        val hint = synchronized(hints) {
            val s = _state.value
            if (s.mode == CaptureMode.Code) null else hints.onFrame(quad, frameAspect, signals, s.flash, now)
        }
        _state.update {
            it.copy(
                quad = result.quad,
                progress = result.progress,
                frameAspect = frameAspect,
                status = status,
                glare = steadyGlare && result.quad != null,
                hint = hint,
            )
        }
    }

    /** A new file for the camera to write into. */
    fun newCaptureFile(): File = File(sessionDir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    /**
     * [turn] is how the pages are taken, [sensed] how the phone is actually held; they differ
     * when the orientation is locked, and each photo is then turned to match [turn].
     */
    fun setDeviceTurn(turn: Int, sensed: Int) {
        deviceTurn = turn
        sensedTurn = sensed
    }

    /** Auto, then always portrait, then always landscape; kept for next time. */
    fun cycleOrientation() {
        val next = CameraOrientation.entries[(_state.value.orientation.ordinal + 1) % CameraOrientation.entries.size]
        _state.update { it.copy(orientation = next, orientationChanged = true) }
        viewModelScope.launch { settings.setCameraOrientation(next) }
        orientationNotice?.cancel()
        orientationNotice = viewModelScope.launch {
            kotlinx.coroutines.delay(ORIENTATION_NOTICE_MS)
            _state.update { it.copy(orientationChanged = false) }
        }
    }

    fun onShutterPressed() {
        _state.update { it.copy(busy = true) }
    }

    fun onPictureSaved(file: File) {
        val auto = autoShot
        autoShot = false
        // The camera turned the photo for how the phone was held; a locked orientation wants
        // it the other way.
        val extra = (deviceTurn - sensedTurn).mod(360)
        if (extra != 0) runCatching { turnPhoto(file, extra) }
        synchronized(tracker) { tracker.onCaptured() }
        capturedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                busy = false,
                status = CaptureStatus.Captured,
                // The live outline is in screen coordinates, which match the photo only when the
                // phone is upright; turned, the outline found in the photo itself is used.
                captures = it.captures + CapturedImage(
                    file,
                    liveQuad = it.quad.takeIf { deviceTurn == 0 }?.let { q -> photoAspect(file)?.let { pa -> FrameToPhoto.map(q, it.frameAspect, pa) } },
                ),
            )
        }
        // Find the page in the full photo now, while the next one is lined up, so it's ready
        // to adjust and Done doesn't have to wait for it.
        val kind = _state.value.mode.kind ?: DocumentKind.Document
        viewModelScope.launch {
            val seen = _state.value.captures.firstOrNull { it.file == file }?.liveQuad
            val found = runCatching { pages.findPage(file, kind, seen) }.getOrNull()
            if (found != null) {
                _state.update { s -> s.copy(captures = s.captures.map { if (it.file == file && it.quad == null) it.copy(quad = found) else it }) }
            }
            // No page found in the photo: it's compared whole, or where the live outline was.
            val outline = found ?: _state.value.captures.firstOrNull { it.file == file }?.liveQuad ?: Quad.Full
            checkDuplicate(file, outline, auto)
        }
    }

    /**
     * Compares the page just taken with the ones taken before in this scan, and with the
     * document's own pages when adding to one. The same page again, taken automatically, is left
     * out (the camera was likely held over a page already done); taken by hand, it's kept, as it
     * may be meant as a retake. Either way the screen says so, with a way to change it.
     */
    private suspend fun checkDuplicate(file: File, quad: Quad, auto: Boolean) {
        if (_state.value.mode == CaptureMode.IdCard) return
        val mine = pages.signature(file, quad) ?: return
        val existing = documentPages ?: (documentId?.let { pages.pageSignatures(it) } ?: emptyList()).also { documentPages = it }
        val before = _state.value.captures.takeWhile { it.file != file }
        val matchExisting = existing.indexOfFirst { it != null && PageSignature.similarity(mine, it) >= PageSignature.SAME }
        val page = if (matchExisting >= 0) {
            matchExisting + 1
        } else {
            before.indexOfFirst { c -> signatures[c.file]?.let { PageSignature.similarity(mine, it) >= PageSignature.SAME } == true }
                .takeIf { it >= 0 }?.let { existing.size + it + 1 }
        }
        signatures[file] = mine
        page ?: return
        val capture = _state.value.captures.firstOrNull { it.file == file } ?: return
        _state.update { s ->
            s.copy(
                captures = if (auto) s.captures.filter { it.file != file } else s.captures,
                duplicate = Duplicate(capture, page, left = auto),
            )
        }
    }

    /** Puts back a capture that was left out as a repeat. */
    fun keepDuplicate() = _state.update { s ->
        val d = s.duplicate ?: return@update s
        s.copy(captures = if (d.left && s.captures.none { it.file == d.capture.file }) s.captures + d.capture else s.captures, duplicate = null)
    }

    /** Takes out a capture that was kept though it's a repeat. */
    fun removeDuplicate() {
        val d = _state.value.duplicate ?: return
        _state.update { it.copy(duplicate = null) }
        if (!d.left) deleteCapture(d.capture.file)
    }

    fun dismissDuplicate() = _state.update { it.copy(duplicate = null) }

    /** Opens the pages taken so far, to fix an outline or drop a page, without ending the scan. */
    fun openPages() = _state.update { if (it.captures.isEmpty()) it else it.copy(reviewing = true) }

    fun closePages() = _state.update { it.copy(reviewing = false) }

    /** Sets a captured page's outline by hand. */
    fun setOutline(file: File, quad: Quad) =
        _state.update { s -> s.copy(captures = s.captures.map { if (it.file == file) it.copy(quad = quad) else it }) }

    /** Moves one corner of a captured page's outline, keeping the corners moved before. */
    fun moveCorner(file: File, corner: Int, x: Float, y: Float) = _state.update { s ->
        s.copy(
            captures = s.captures.map { capture ->
                if (capture.file != file) return@map capture
                val quad = capture.quad ?: capture.liveQuad ?: Quad.Full
                capture.copy(quad = Quad.of(quad.corners.mapIndexed { i, c -> if (i == corner) Corner(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else c }))
            },
        )
    }

    /** Drops one captured page; with none left, back to the camera. */
    fun deleteCapture(file: File) {
        _state.update { s ->
            val left = s.captures.filter { it.file != file }
            s.copy(captures = left, reviewing = s.reviewing && left.isNotEmpty())
        }
        viewModelScope.launch(Dispatchers.IO) { file.delete() }
    }

    fun onPictureFailed() {
        _state.update { it.copy(busy = false) }
    }

    fun setAutoCapture(enabled: Boolean) = _state.update { it.copy(autoCapture = enabled) }

    fun toggleFlash() = _state.update { it.copy(flash = !it.flash) }

    /** Saves the fixes for every scan from now on, this one included. */
    fun setFixes(fixes: ScanFixes) {
        _state.update { it.copy(fixes = fixes) }
        viewModelScope.launch { settings.setScanFixes(fixes) }
    }

    fun save(title: String) {
        val captures = _state.value.captures
        if (captures.isEmpty() || _state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatching {
                if (documentId == null) {
                    val kind = _state.value.mode.kind ?: DocumentKind.Document
                    SavedScan(pages.createDocument(title, folderId = null, captures, kind), 0)
                } else {
                    val before = pages.pages(documentId).size
                    pages.addPages(documentId, captures)
                    SavedScan(documentId, before)
                }
            }
            result.onSuccess { saved.send(it) }
            _state.update { it.copy(saving = false, reviewing = false, captures = if (result.isSuccess) emptyList() else it.captures) }
        }
    }

    /** Imports photos as the current mode, e.g. an old picture of a book spread. */
    fun import(uris: List<android.net.Uri>, title: String) {
        if (uris.isEmpty() || _state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatching {
                val before = documentId?.let { pages.pages(it).size } ?: 0
                val kind = _state.value.mode.kind ?: DocumentKind.Document
                SavedScan(pages.importImages(uris, title, documentId, kind), before)
            }
            _state.update { it.copy(saving = false) }
            result.onSuccess { saved.send(it) }
        }
    }

    fun discard() {
        _state.update { it.copy(captures = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) { sessionDir.deleteRecursively() }
    }

    override fun onCleared() {
        // Saved captures were moved out already; anything left is abandoned.
        val dir = sessionDir
        Thread { dir.deleteRecursively() }.start()
    }

    /** The photo's width ÷ height as it stands upright, or null if it can't be read. */
    private fun photoAspect(file: File): Float? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        val turned = ExifInterface(file).rotationDegrees % 180 != 0
        val w = if (turned) bounds.outHeight else bounds.outWidth
        val h = if (turned) bounds.outWidth else bounds.outHeight
        (w.toFloat() / h).takeIf { it.isFinite() && it > 0f }
    }.getOrNull()

    /** Turns [file] clockwise by [degrees] by its EXIF orientation, without re-encoding it. */
    private fun turnPhoto(file: File, degrees: Int) {
        val exif = ExifInterface(file)
        val turned = (exif.rotationDegrees + degrees) % 360
        val tag = when (turned) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, tag.toString())
        exif.saveAttributes()
    }

    /** The next photo was asked for by auto-capture, not the shutter. */
    @Volatile private var autoShot = false
    /** Each capture's page signature, once found. */
    private val signatures = java.util.concurrent.ConcurrentHashMap<File, FloatArray>()
    /** The signatures of the document's pages when adding to one, read once. */
    private var documentPages: List<FloatArray?>? = null

    private var orientationNotice: kotlinx.coroutines.Job? = null

    private companion object {
        const val ORIENTATION_NOTICE_MS = 2000L
        const val CAPTURED_HOLD_MS = 900L
        const val GLARE_WINDOW = 6
        const val GLARE_NEEDED = 4
    }
}
