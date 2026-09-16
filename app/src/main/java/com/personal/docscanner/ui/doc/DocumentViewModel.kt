package com.personal.docscanner.ui.doc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.data.db.PageEntity
import com.personal.docscanner.data.model.DocumentDetail
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.data.model.PdfPageSize
import com.personal.docscanner.data.storage.StorageManager
import com.personal.docscanner.net.WifiTransferServer
import com.personal.docscanner.scan.OcrEngine
import com.personal.docscanner.scan.PdfCompressor
import com.personal.docscanner.scan.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as DocScannerApp).repository
    private val prefs = (app as DocScannerApp).prefs
    private val ocr = (app as DocScannerApp).ocr
    private val tessData = (app as DocScannerApp).tessData

    private val _documentId = MutableStateFlow("")

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A finished file waiting to be shared; the UI turns it into an intent. */
    private val _shareFile = MutableStateFlow<File?>(null)
    val shareFile: StateFlow<File?> = _shareFile.asStateFlow()

    private val _ocrMissingLangs = MutableStateFlow<List<String>>(emptyList())
    val ocrMissingLangs: StateFlow<List<String>> = _ocrMissingLangs.asStateFlow()

    sealed interface SummaryState {
        data class Done(val text: String) : SummaryState
        data object NeedsOcr : SummaryState
        data object Empty : SummaryState
    }

    private val _summary = MutableStateFlow<SummaryState?>(null)
    val summary: StateFlow<SummaryState?> = _summary.asStateFlow()

    private val _detail = MutableStateFlow<DocumentDetail?>(null)
    val detail: StateFlow<DocumentDetail?> = _detail.asStateFlow()

    val settings = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        com.personal.docscanner.data.prefs.Settings()
    )

    fun load(documentId: String) {
        if (_documentId.value == documentId) return
        _documentId.value = documentId
        viewModelScope.launch {
            repo.observeDocument(documentId).collect { _detail.value = it }
        }
    }

    // ------------------------------------------------------------- metadata

    fun setTitle(title: String) {
        viewModelScope.launch { repo.updateDocument(_documentId.value, title = title) }
    }

    fun setNote(note: String) {
        viewModelScope.launch { repo.updateDocument(_documentId.value, note = note) }
    }

    fun setTags(tags: String) {
        viewModelScope.launch { repo.updateDocument(_documentId.value, tags = tags) }
    }

    fun setField(fieldId: String, value: String) {
        viewModelScope.launch { repo.setFieldValue(_documentId.value, fieldId, value) }
    }

    suspend fun fieldSuggestions(fieldId: String): List<String> = repo.fieldSuggestions(fieldId)

    // ---------------------------------------------------------------- pages

    /** File paths are derived from the page row, never stored, so relocating
     *  the library directory cannot leave dangling references. */
    fun pageThumbPath(page: PageEntity): String =
        repo.storage.pagePath(page.documentId, page.thumbName)

    fun pageImagePath(page: PageEntity): String =
        repo.storage.pagePath(page.documentId, page.fileName)

    fun deletePage(pageId: String) {
        viewModelScope.launch { repo.deletePage(pageId) }
    }

    fun movePage(pageId: String, up: Boolean) {
        viewModelScope.launch {
            val pages = repo.pagesOf(_documentId.value).toMutableList()
            val index = pages.indexOfFirst { it.id == pageId }
            val target = if (up) index - 1 else index + 1
            if (index < 0 || target !in pages.indices) return@launch
            val moved = pages.removeAt(index)
            pages.add(target, moved)
            repo.reorderPages(_documentId.value, pages.map { it.id })
        }
    }

    /**
     * Writes a full new page order in one go — what a drag in the pages strip
     * ends with, rather than the single-step nudge [movePage] does for the
     * reorder menu entries.
     */
    fun reorderPages(orderedIds: List<String>) {
        viewModelScope.launch { repo.reorderPages(_documentId.value, orderedIds) }
    }

    fun setPageFilter(pageId: String, filter: PageFilter) {
        viewModelScope.launch {
            _busy.value = FILTERING
            repo.updatePage(pageId, filter = filter)
            _busy.value = null
        }
    }

    fun rotatePage(pageId: String, degrees: Int) {
        viewModelScope.launch {
            _busy.value = FILTERING
            val current = repo.pagesOf(_documentId.value).firstOrNull { it.id == pageId }
            repo.updatePage(pageId, rotation = ((current?.rotation ?: 0) + degrees + 360) % 360)
            _busy.value = null
        }
    }

    /** Applies one look to every page — the usual case once a document is done. */
    fun applyFilterToAll(filter: PageFilter) {
        viewModelScope.launch {
            val pages = repo.pagesOf(_documentId.value)
            _busy.value = FILTERING
            pages.forEachIndexed { index, page ->
                _progress.value = index.toFloat() / pages.size
                repo.updatePage(page.id, filter = filter)
            }
            _progress.value = null
            _busy.value = null
        }
    }

    fun deleteDocument(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteDocument(_documentId.value)
            onDone()
        }
    }

    // -------------------------------------------------------------- exports

    fun exportPdf() = runExport(EXPORTING) {
        val s = prefs.settings.first()
        repo.exportPdf(_documentId.value, s.pdfPageSize, s.pdfQuality)
    }

    fun exportImages() = runExport(EXPORTING) { repo.exportImages(_documentId.value) }

    fun exportText() = runExport(EXPORTING) { repo.exportText(_documentId.value) }

    private fun runExport(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _busy.value = label
            runCatching { block() }
                .onSuccess { _message.value = it }
                .onFailure { _message.value = it.message ?: "failed" }
            _busy.value = null
        }
    }

    /**
     * Builds the PDF and parks it for sharing. Nothing is published to shared
     * storage on this path — the file stays in the app's cache and is handed
     * over as a content:// URI.
     */
    fun prepareForSharing() {
        viewModelScope.launch {
            _busy.value = EXPORTING
            runCatching {
                val s = prefs.settings.first()
                repo.buildPdf(_documentId.value, s.pdfPageSize, s.pdfQuality)
            }.onSuccess { _shareFile.value = it }
                .onFailure { _message.value = it.message ?: "failed" }
            _busy.value = null
        }
    }

    /**
     * Compresses to a hard byte ceiling, for portals that cap attachment size,
     * then parks the result for sharing *and* saves a copy to Documents.
     */
    fun compressTo(targetBytes: Long) {
        viewModelScope.launch {
            _busy.value = COMPRESSING
            _progress.value = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val doc = repo.document(_documentId.value) ?: error("document not found")
                    val pages = repo.pagesOf(_documentId.value)
                        .map { repo.storage.pageFile(_documentId.value, it.fileName) }
                        .filter { it.exists() }
                    require(pages.isNotEmpty()) { "no pages" }

                    val base = StorageManager.sanitizeFileName(doc.title)
                    val label = PdfCompressor.humanTarget(targetBytes).replace(" ", "")
                    val staging = repo.storage.tempFile("${base}_$label.pdf")

                    PdfCompressor.compressToTarget(
                        pageFiles = pages,
                        staging = staging,
                        targetBytes = targetBytes,
                        pageSize = PdfPageSize.A4
                    )
                }
            }.onSuccess { result ->
                _shareFile.value = result.file
                val actual = StorageManager.humanSize(result.bytes)
                val target = PdfCompressor.humanTarget(result.targetBytes)
                _message.value = if (result.metTarget) {
                    "$actual / $target ✓"
                } else {
                    "⚠ $actual — $target"
                }
                // Keep a copy on device storage too, so it survives the cache.
                runCatching {
                    repo.storage.exportToDocuments(
                        result.file, result.file.name, "application/pdf"
                    )
                }
            }.onFailure { _message.value = it.message ?: "compression failed" }
            _busy.value = null
            _progress.value = null
        }
    }

    fun consumeShareFile() {
        _shareFile.value = null
    }

    // ------------------------------------------------------------------ OCR

    fun runOcr() {
        viewModelScope.launch {
            val langs = prefs.settings.first().ocrLanguages
            val missing = ocr.missingLanguages(langs)
            if (missing.isNotEmpty()) {
                _ocrMissingLangs.value = missing
                return@launch
            }

            _busy.value = OCR
            _progress.value = 0f
            val files = repo.pagesOf(_documentId.value)
                .map { repo.storage.pageFile(_documentId.value, it.fileName) }

            when (val result = ocr.recognize(files, langs) { done, total ->
                _progress.value = done.toFloat() / total
            }) {
                is OcrEngine.Result.Success -> {
                    repo.saveOcrText(_documentId.value, result.text)
                    if (result.text.isBlank()) _message.value = OCR_EMPTY
                }
                is OcrEngine.Result.MissingLanguages -> _ocrMissingLangs.value = result.languages
                is OcrEngine.Result.Failure -> _message.value = result.message
            }
            _progress.value = null
            _busy.value = null
        }
    }

    fun downloadOcrLanguages() {
        viewModelScope.launch {
            _busy.value = DOWNLOADING
            _progress.value = 0f
            val langs = prefs.settings.first().ocrLanguages
            runCatching {
                tessData.download(langs) { fraction -> _progress.value = fraction }
            }.onSuccess {
                _ocrMissingLangs.value = emptyList()
                runOcr()
            }.onFailure { _message.value = it.message ?: "download failed" }
            _progress.value = null
            _busy.value = null
        }
    }

    fun dismissOcrPrompt() {
        _ocrMissingLangs.value = emptyList()
    }

    // -------------------------------------------------------------- summary

    /**
     * Summarizes the document's already-extracted OCR text. Reuses [OCR]
     * rather than a state of its own: this genuinely is the same "working"
     * moment as OCR to the UI, and the two never run at once since summarizing
     * finishes in milliseconds once the text exists.
     */
    fun summarize() {
        viewModelScope.launch {
            val text = _detail.value?.doc?.ocrText.orEmpty()
            if (text.isBlank()) {
                _summary.value = SummaryState.NeedsOcr
                return@launch
            }
            _busy.value = SUMMARIZE
            val result = withContext(Dispatchers.Default) { Summarizer.summarize(text) }
            _summary.value = if (result.isNullOrBlank()) SummaryState.Empty else SummaryState.Done(result)
            _busy.value = null
        }
    }

    fun dismissSummary() {
        _summary.value = null
    }

    // ------------------------------------------------------- Wi-Fi transfer

    sealed interface WifiTransferState {
        data class Ready(val url: String) : WifiTransferState
        data object Served : WifiTransferState
        data object Unavailable : WifiTransferState
    }

    private val _wifiTransfer = MutableStateFlow<WifiTransferState?>(null)
    val wifiTransfer: StateFlow<WifiTransferState?> = _wifiTransfer.asStateFlow()

    private var wifiServer: WifiTransferServer? = null

    /**
     * Builds the document's PDF and serves it from an in-process HTTP server,
     * so a computer on the same Wi-Fi can pull it into a browser without a
     * cable, an email, or leaving the local network at all.
     */
    fun startWifiTransfer() {
        viewModelScope.launch {
            _busy.value = EXPORTING
            val pdf = runCatching {
                val s = prefs.settings.first()
                repo.buildPdf(_documentId.value, s.pdfPageSize, s.pdfQuality)
            }.getOrNull()
            _busy.value = null

            if (pdf == null) {
                _wifiTransfer.value = WifiTransferState.Unavailable
                return@launch
            }
            val server = WifiTransferServer(pdf, pdf.name)
            server.onServed = { _wifiTransfer.value = WifiTransferState.Served }
            val info = server.start()
            if (info == null) {
                _wifiTransfer.value = WifiTransferState.Unavailable
                return@launch
            }
            wifiServer = server
            _wifiTransfer.value = WifiTransferState.Ready(info.url)
        }
    }

    fun stopWifiTransfer() {
        wifiServer?.stop()
        wifiServer = null
        _wifiTransfer.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        wifiServer?.stop()
    }

    companion object {
        const val EXPORTING = "exporting"
        const val COMPRESSING = "compressing"
        const val OCR = "ocr"
        const val DOWNLOADING = "downloading"
        const val FILTERING = "filtering"
        const val OCR_EMPTY = "ocr_empty"
        const val SUMMARIZE = "summarize"
    }
}
