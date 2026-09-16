package com.personal.docscanner.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.R
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.scan.DocumentFormat
import com.personal.docscanner.scan.EdgeDetector
import com.personal.docscanner.scan.ImageProcessor
import com.personal.docscanner.scan.OcrEngine
import com.personal.docscanner.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carries a scan across the camera → crop → save hop.
 *
 * Scoped to the activity rather than to a screen, because the whole point is
 * that the capture outlives the camera screen. Bitmaps are held here rather
 * than passed through navigation arguments, which can only carry primitives.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as DocScannerApp).repository
    private val prefs = (app as DocScannerApp).prefs
    private val ocr = (app as DocScannerApp).ocr
    private val tessData = (app as DocScannerApp).tessData

    data class Session(
        /** Where a newly created document should land. */
        val folderId: String? = null,
        /** Set when pages are being added to a document that already exists. */
        val documentId: String? = null,
        /** Pages saved so far in this session, for the counter on the shutter. */
        val savedPages: Int = 0
    )

    data class Pending(
        val bitmap: Bitmap,
        val quad: Quad?,
        val filter: PageFilter,
        val rotation: Int = 0,
        val brightness: Int = 0,
        val contrast: Int = 0,
        /** Chosen by hand on the crop screen; null means detect from the shape. */
        val snapTo: DocumentFormat? = null
    )

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** A page being filed straight from the camera, without leaving it. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Thumbnail of the page just filed, for the corner of the viewfinder.
     *
     * Since the crop screen never appears at capture time, without this there
     * is no moment where you see what was actually kept — and a bad crop would
     * only turn up later, after the whole stack had been shot.
     */
    private val _lastPageThumb = MutableStateFlow<String?>(null)
    val lastPageThumb: StateFlow<String?> = _lastPageThumb.asStateFlow()

    /**
     * Why the corners are where they are: null when they were detected, otherwise
     * a string resource explaining what went wrong. Without this, a failed detect
     * and an unavailable OpenCV look identical on screen — both just show a
     * default rectangle — and there is no way to tell them apart on the device.
     */
    private val _detectHint = MutableStateFlow<Int?>(null)
    val detectHint: StateFlow<Int?> = _detectHint.asStateFlow()

    /** Set while the crop screen is re-cropping a page that is already saved. */
    private val _editingPage = MutableStateFlow(false)
    val editingPage: StateFlow<Boolean> = _editingPage.asStateFlow()

    /**
     * ID-card capture: true once the user turns the mode on from the camera
     * screen. The next two shutter presses are treated as the front then the
     * back of one card instead of two ordinary pages.
     */
    private val _idCardMode = MutableStateFlow(false)
    val idCardMode: StateFlow<Boolean> = _idCardMode.asStateFlow()

    /** The front shot, held here while waiting for the back. */
    private val _idCardFront = MutableStateFlow<Bitmap?>(null)

    /** True once the front has been shot and the UI should prompt for the back. */
    private val _idCardFrontPending = MutableStateFlow(false)
    val idCardFrontPending: StateFlow<Boolean> = _idCardFrontPending.asStateFlow()

    fun setIdCardMode(enabled: Boolean) {
        _idCardMode.value = enabled
        _idCardFront.value?.let { if (!it.isRecycled) it.recycle() }
        _idCardFront.value = null
        _idCardFrontPending.value = false
    }

    private var editingPageId: String? = null
        set(value) {
            field = value
            _editingPage.value = value != null
        }

    private fun noteDetection(found: Boolean) {
        _detectHint.value = when {
            found -> null
            !EdgeDetector.isAvailable() -> R.string.detect_unavailable
            else -> R.string.detect_none
        }
    }

    /** The document this session has been writing into, once one exists. */
    val currentDocumentId: String? get() = _session.value.documentId

    fun start(folderId: String?, documentId: String? = null) {
        _session.value = Session(folderId = folderId, documentId = documentId)
        _lastPageThumb.value = null
        clearPending()
    }

    /**
     * A photo picked from outside the camera during an open session.
     *
     * Filed exactly like a shutter press — straight in, uncropped, nothing
     * detected yet — so it is swept through edge detection and enhancement
     * together with every camera shot when [finish] runs. Bringing in an
     * outside picture no longer has to interrupt the session with a crop
     * screen of its own.
     */
    fun onImported(uri: Uri) {
        viewModelScope.launch {
            _saving.value = true
            try {
                val bitmap = repo.decodeImport(uri)
                if (bitmap == null) {
                    _error.value = "Could not read that image"
                    return@launch
                }
                try {
                    val docId = _session.value.documentId ?: repo.createDocument(_session.value.folderId)
                    val page = repo.addRawPage(docId, bitmap)
                    _lastPageThumb.value = repo.storage.pagePath(docId, page.thumbName)
                    _session.value = _session.value.copy(
                        documentId = docId,
                        savedPages = _session.value.savedPages + 1
                    )
                } finally {
                    bitmap.recycle()
                }
            } catch (t: Throwable) {
                _error.value = t.message
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * A shutter press: file the raw shot as a page of the current document,
     * without ever showing the crop screen, so the camera stays up and the
     * next sheet can be shot with another press. Nothing is detected, cropped
     * or enhanced here — that happens for every page of the document at once
     * in [finish], once the whole stack has been shot. The document is only
     * closed out when the user taps "تم".
     *
     * Saving runs off [_busy] deliberately — [_busy] darkens the whole screen,
     * and blacking out the viewfinder between pages is exactly what makes a
     * batch of twenty documents unpleasant to shoot. Skipping detection here
     * also means each shutter press is now just a file write: faster, and one
     * less reason a press could ever feel like it stalled.
     */
    fun capturePage(
        bitmap: Bitmap,
        previewQuad: Quad? = null,
        previewAspect: Float = 0f,
        onSaved: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _saving.value = true
            try {
                val docId = _session.value.documentId
                    ?: repo.createDocument(_session.value.folderId)
                val page = repo.addRawPage(documentId = docId, original = bitmap)
                _lastPageThumb.value = repo.storage.pagePath(docId, page.thumbName)
                _session.value = _session.value.copy(
                    documentId = docId,
                    savedPages = _session.value.savedPages + 1
                )
                onSaved()
            } catch (t: Throwable) {
                _error.value = t.message
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * A shutter press while [idCardMode] is on. The first press holds the shot
     * as the card's front and asks (via [onNeedBack]) for the back; the second
     * press composites both sides into one page through
     * [com.personal.docscanner.data.repo.DocumentRepository.addIdCardPage] and
     * turns the mode back off, exactly like a normal capture otherwise.
     */
    fun captureIdCardShot(
        bitmap: Bitmap,
        onNeedBack: () -> Unit,
        onSaved: () -> Unit = {}
    ) {
        val front = _idCardFront.value
        if (front == null) {
            _idCardFront.value = bitmap
            _idCardFrontPending.value = true
            onNeedBack()
            return
        }
        viewModelScope.launch {
            _saving.value = true
            try {
                val docId = _session.value.documentId
                    ?: repo.createDocument(_session.value.folderId)
                val page = repo.addIdCardPage(docId, front, bitmap)
                _lastPageThumb.value = repo.storage.pagePath(docId, page.thumbName)
                _session.value = _session.value.copy(
                    documentId = docId,
                    savedPages = _session.value.savedPages + 1
                )
                onSaved()
            } catch (t: Throwable) {
                _error.value = t.message
            } finally {
                front.recycle()
                bitmap.recycle()
                _idCardFront.value = null
                _idCardFrontPending.value = false
                _idCardMode.value = false
                _saving.value = false
            }
        }
    }

    /**
     * The end of the session: runs edge detection and enhancement over every
     * page shot or imported since [start], all at once, then hands the
     * document id to [onDone]. This is the one point where the whole stack
     * gets looked at together instead of frame by frame — a document scanned
     * in inconsistent light ends up with every page corrected the same way,
     * since the same detector pass and filter run over the whole batch.
     *
     * Runs under [_busy] rather than [_saving]: unlike a single shutter press,
     * a full sweep over a twenty-page document can take a few seconds, and
     * that is worth telling the user about with the loading overlay.
     */
    fun finish(onDone: (String?) -> Unit) {
        val docId = _session.value.documentId
        if (docId == null) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                val filter = prefs.settings.first().defaultFilter
                repo.processPendingPages(docId, filter)
            } catch (t: Throwable) {
                _error.value = t.message
            } finally {
                _busy.value = false
            }
            onDone(docId)
        }
    }

    // --------------------------------------------------------- quick OCR

    /** State of the camera screen's "extract text now" action. */
    sealed interface QuickOcr {
        data object Running : QuickOcr
        data class Done(val text: String) : QuickOcr
        data class MissingLanguages(val languages: List<String>) : QuickOcr
        data class Failed(val message: String) : QuickOcr
    }

    private val _quickOcr = MutableStateFlow<QuickOcr?>(null)
    val quickOcr: StateFlow<QuickOcr?> = _quickOcr.asStateFlow()

    /**
     * Held only across a [QuickOcr.MissingLanguages] result, so
     * [downloadQuickOcrLanguages] can retry the very shot that produced it once
     * the language pack lands, without the camera screen having to keep its
     * own copy of a bitmap this view model already owns.
     */
    private var pendingQuickOcrBitmap: Bitmap? = null

    /**
     * A dedicated shutter press that never joins the document: shoot, crop to
     * whatever page or card is in frame, and read the text off it right away.
     * For a quick "what does this say" moment there is no reason to make the
     * user finish a whole scanning session first just to reach the OCR menu
     * item on the saved document.
     *
     * A [QuickOcr.MissingLanguages] result leaves [bitmap] un-recycled (see
     * [pendingQuickOcrBitmap]); every other outcome recycles it here.
     */
    fun quickOcr(bitmap: Bitmap) {
        viewModelScope.launch {
            _quickOcr.value = QuickOcr.Running
            var recycleBitmap = true
            try {
                val langs = prefs.settings.first().ocrLanguages
                val cropped = withContext(Dispatchers.Default) {
                    val quad = EdgeDetector.detect(bitmap)
                    if (quad != null) ImageProcessor.warp(bitmap, quad) else bitmap
                }
                _quickOcr.value = when (val result = ocr.recognizeBitmap(cropped, langs)) {
                    is OcrEngine.Result.Success -> QuickOcr.Done(result.text)
                    is OcrEngine.Result.MissingLanguages -> {
                        recycleBitmap = false
                        pendingQuickOcrBitmap = bitmap
                        QuickOcr.MissingLanguages(result.languages)
                    }
                    is OcrEngine.Result.Failure -> QuickOcr.Failed(result.message)
                }
                if (cropped !== bitmap) cropped.recycle()
            } catch (t: Throwable) {
                _quickOcr.value = QuickOcr.Failed(t.message ?: "OCR failed")
            } finally {
                if (recycleBitmap) bitmap.recycle()
            }
        }
    }

    /** Downloads the missing language data, then retries the shot that asked for it. */
    fun downloadQuickOcrLanguages() {
        val bitmap = pendingQuickOcrBitmap ?: return
        pendingQuickOcrBitmap = null
        viewModelScope.launch {
            _quickOcr.value = QuickOcr.Running
            val langs = prefs.settings.first().ocrLanguages
            runCatching { tessData.download(langs) }
                .onSuccess { quickOcr(bitmap) }
                .onFailure {
                    _quickOcr.value = QuickOcr.Failed(it.message ?: "download failed")
                    bitmap.recycle()
                }
        }
    }

    fun dismissQuickOcr() {
        pendingQuickOcrBitmap?.recycle()
        pendingQuickOcrBitmap = null
        _quickOcr.value = null
    }

    fun updateQuad(quad: Quad) {
        _pending.value = _pending.value?.copy(quad = quad)
    }

    fun updateSnapFormat(format: DocumentFormat?) {
        _pending.value = _pending.value?.copy(snapTo = format)
    }

    fun updateFilter(filter: PageFilter) {
        _pending.value = _pending.value?.copy(filter = filter)
    }

    fun updateAdjustments(brightness: Int, contrast: Int) {
        _pending.value = _pending.value?.copy(brightness = brightness, contrast = contrast)
    }

    fun rotate(degrees: Int) {
        val current = _pending.value ?: return
        _pending.value = current.copy(rotation = (current.rotation + degrees + 360) % 360)
    }

    fun selectWholeImage() {
        val current = _pending.value ?: return
        _pending.value = current.copy(
            quad = Quad.full(current.bitmap.width, current.bitmap.height)
        )
    }

    /**
     * Re-runs detection, optionally told in advance what shape to look for.
     *
     * Without [expectedFormat] this is the general-purpose pass tuned for a
     * full page. Passing a format such as [DocumentFormat.ID_CARD] switches to
     * a pass that accepts a much smaller candidate and rejects one whose shape
     * does not roughly match — otherwise a card sitting on a table is no
     * different from any other page-sized subject, and the same generic pass
     * that finds a full sheet of paper instead grabs the table it is on.
     */
    fun autoDetect(expectedFormat: DocumentFormat? = null) {
        val current = _pending.value ?: return
        viewModelScope.launch {
            val ratio = expectedFormat?.takeIf { it.isKnown }?.ratio
            val detected = withContext(Dispatchers.Default) {
                EdgeDetector.detect(current.bitmap, expectedRatio = ratio)
            }
            noteDetection(detected != null)
            val quad = detected
                ?: ratio?.let { Quad.insetForAspect(current.bitmap.width, current.bitmap.height, it) }
                ?: Quad.inset(current.bitmap.width, current.bitmap.height)
            _pending.value = _pending.value?.copy(quad = quad, snapTo = expectedFormat ?: _pending.value?.snapTo)
        }
    }

    /**
     * Opens an already-saved page for re-cropping.
     *
     * Every page keeps its untouched original alongside the processed version,
     * so this re-opens that original with the corners it was last saved with.
     * Nothing is destroyed by editing: the page can be re-cropped as many times
     * as needed, always from the full photograph.
     */
    fun startPageEdit(pageId: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val edit = repo.pageForEdit(pageId) ?: return@launch
                editingPageId = pageId
                _session.value = _session.value.copy(documentId = edit.page.documentId)
                _detectHint.value = null
                val saved = Quad.parse(edit.page.quad)
                val quad = saved ?: withContext(Dispatchers.Default) {
                    EdgeDetector.detect(edit.original)
                } ?: Quad.full(edit.original.width, edit.original.height)
                _pending.value = Pending(
                    bitmap = edit.original,
                    quad = quad,
                    filter = PageFilter.fromName(edit.page.filter),
                    rotation = edit.page.rotation,
                    brightness = edit.page.brightness,
                    contrast = edit.page.contrast,
                    snapTo = DocumentFormat.parse(edit.page.snapFormat)
                )
            } catch (t: Throwable) {
                _error.value = t.message
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Writes the pending capture as a page, creating the document on the first
     * page of the session.
     *
     * @return the document id the page landed in, or null on failure.
     */
    suspend fun commitPending(): String? {
        val pending = _pending.value ?: return null
        editingPageId?.let { return commitPageEdit(it, pending) }
        _busy.value = true
        return try {
            val docId = _session.value.documentId ?: repo.createDocument(_session.value.folderId)
            repo.addPage(
                documentId = docId,
                original = pending.bitmap,
                quad = pending.quad,
                filter = pending.filter,
                rotation = pending.rotation,
                brightness = pending.brightness,
                contrast = pending.contrast
            )
            _session.value = _session.value.copy(
                documentId = docId,
                savedPages = _session.value.savedPages + 1
            )
            clearPending()
            docId
        } catch (t: Throwable) {
            _error.value = t.message
            null
        } finally {
            _busy.value = false
        }
    }

    /** A live preview of the current filter, computed off the main thread. */
    suspend fun previewOf(pending: Pending, maxEdge: Int = 900): Bitmap =
        withContext(Dispatchers.Default) {
            val quad = pending.quad
            val warped = if (quad != null) ImageProcessor.warp(pending.bitmap, quad) else pending.bitmap
            val rotated = ImageProcessor.rotate(warped, pending.rotation)
            if (rotated !== warped && warped !== pending.bitmap) warped.recycle()
            val small = ImageProcessor.limitSize(rotated, maxEdge)
            if (small !== rotated && rotated !== pending.bitmap) rotated.recycle()
            ImageProcessor.applyFilter(small, pending.filter, pending.brightness, pending.contrast)
        }

    /** Writes a re-crop back onto the page it came from. */
    private suspend fun commitPageEdit(pageId: String, pending: Pending): String? {
        _busy.value = true
        return try {
            repo.updatePage(
                pageId = pageId,
                quad = pending.quad,
                filter = pending.filter,
                rotation = pending.rotation,
                brightness = pending.brightness,
                contrast = pending.contrast,
                snapTo = pending.snapTo
            )
            val docId = _session.value.documentId
            clearPending()
            docId
        } catch (t: Throwable) {
            _error.value = t.message
            null
        } finally {
            _busy.value = false
        }
    }

    fun clearPending() {
        _pending.value = null
        // Leaving this set would send the next capture into updatePage and
        // overwrite a page the user never asked to touch.
        editingPageId = null
    }

    fun consumeError() {
        _error.value = null
    }

    /** Drops the whole session; the bitmap is released with it. */
    fun reset() {
        _pending.value?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        _pending.value = null
        editingPageId = null
        _lastPageThumb.value = null
        _session.value = Session()
        _idCardFront.value?.let { if (!it.isRecycled) it.recycle() }
        _idCardFront.value = null
        _idCardFrontPending.value = false
        _idCardMode.value = false
    }

    override fun onCleared() {
        super.onCleared()
        _pending.value?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        pendingQuickOcrBitmap?.let { if (!it.isRecycled) it.recycle() }
    }

    @Suppress("unused")
    private fun cacheFile(name: String): File = repo.storage.tempFile(name)
}
