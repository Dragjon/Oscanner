package dev.olaf.oscanner.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.CapturedImage
import dev.olaf.oscanner.core.data.PageStore
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
)

/** Where to go once captures are saved: the document and the index of its first new page. */
data class SavedScan(val documentId: Long, val firstNewPage: Int)

class CaptureViewModel(
    private val pages: PageStore,
    cacheDir: File,
    /** Set when adding pages to an existing document. */
    private val documentId: Long?,
) : ViewModel() {

    private val sessionDir = File(cacheDir, "capture/${UUID.randomUUID()}")
    private val tracker = AutoCaptureTracker()
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val shutter = Channel<Unit>(Channel.CONFLATED)
    /** Auto-capture asks the screen to take a picture through here. */
    val shutterRequests: Flow<Unit> = shutter.receiveAsFlow()

    private val saved = Channel<SavedScan>(Channel.BUFFERED)
    val savedScans: Flow<SavedScan> = saved.receiveAsFlow()

    private var capturedAt = 0L

    init {
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
    fun onFrame(quad: Quad?, sharpness: Double, frameAspect: Float, now: Long = System.currentTimeMillis()) {
        val result = synchronized(tracker) { tracker.onFrame(quad, sharpness, now, _state.value.autoCapture) }
        if (result.fire && !_state.value.busy) shutter.trySend(Unit)
        val status = when {
            now - capturedAt < CAPTURED_HOLD_MS -> CaptureStatus.Captured
            result.quad == null -> CaptureStatus.Searching
            else -> CaptureStatus.HoldSteady
        }
        _state.update { it.copy(quad = result.quad, progress = result.progress, frameAspect = frameAspect, status = status) }
    }

    /** A new file for the camera to write into. */
    fun newCaptureFile(): File = File(sessionDir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    fun onShutterPressed() {
        _state.update { it.copy(busy = true) }
    }

    fun onPictureSaved(file: File) {
        synchronized(tracker) { tracker.onCaptured() }
        capturedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                busy = false,
                status = CaptureStatus.Captured,
                captures = it.captures + CapturedImage(file, liveQuad = it.quad),
            )
        }
    }

    fun onPictureFailed() {
        _state.update { it.copy(busy = false) }
    }

    fun setAutoCapture(enabled: Boolean) = _state.update { it.copy(autoCapture = enabled) }

    fun toggleFlash() = _state.update { it.copy(flash = !it.flash) }

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
            _state.update { it.copy(saving = false, captures = if (result.isSuccess) emptyList() else it.captures) }
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

    private companion object {
        const val CAPTURED_HOLD_MS = 900L
    }
}
